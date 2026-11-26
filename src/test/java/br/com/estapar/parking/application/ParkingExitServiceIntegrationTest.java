package br.com.estapar.parking.application;

import br.com.estapar.parking.ParkingApplication;
import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventType;
import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.domain.model.ParkingSpotStatus;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSpotRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class ParkingExitServiceIntegrationTest {

    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");
    private static final Instant EXIT_TIME = Instant.parse("2025-01-01T13:30:00Z");
    private static final BigDecimal LATITUDE = new BigDecimal("-23.5616840");
    private static final BigDecimal LONGITUDE = new BigDecimal("-46.6559810");
    private static final List<ParkingSessionStatus> ACTIVE_STATUSES =
            List.of(ParkingSessionStatus.ENTERED, ParkingSessionStatus.PARKED);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("estapar_parking")
            .withUsername("estapar")
            .withPassword("estapar")
            .withCommand("--log-bin-trust-function-creators=1");

    private static ConfigurableApplicationContext context;
    private static ParkingEntryService entryService;
    private static ParkingSpotAssignmentService assignmentService;
    private static ParkingExitService exitService;
    private static ParkingSessionRepository parkingSessionRepository;
    private static ParkingSpotRepository parkingSpotRepository;
    private static GarageInitializationState initializationState;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startApplicationContext() {
        context = new SpringApplicationBuilder(ParkingApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--garage.simulator.enabled=false",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword()
                );
        entryService = context.getBean(ParkingEntryService.class);
        assignmentService = context.getBean(ParkingSpotAssignmentService.class);
        exitService = context.getBean(ParkingExitService.class);
        parkingSessionRepository = context.getBean(ParkingSessionRepository.class);
        parkingSpotRepository = context.getBean(ParkingSpotRepository.class);
        initializationState = context.getBean(GarageInitializationState.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopApplicationContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_parking_spot_update");
        jdbcTemplate.update("DELETE FROM parking_session");
        jdbcTemplate.update("DELETE FROM parking_spot");
        jdbcTemplate.update("DELETE FROM sector");
        initializationState.markReady();
    }

    @Test
    void shouldCompleteEntryParkedExitAndReleaseCapacity() {
        long sectorId = prepareParkedSession("ZUL0001", 1, new BigDecimal("10.00"));

        exitService.processExit(exit("ZUL0001", EXIT_TIME));

        ParkingSession session = parkingSessionRepository.findAll().getFirst();
        assertEquals(ParkingSessionStatus.EXITED, session.getStatus());
        assertEquals(EXIT_TIME, session.getExitTime());
        assertEquals(new BigDecimal("25.00"), session.getAmount());
        assertEquals(1L, session.getParkingSpot().getId());
        assertEquals(sectorId, session.getSector().getId());
        assertEquals(new BigDecimal("10.00"), session.getBasePriceAtParking());
        assertEquals(new BigDecimal("1.25"), session.getAppliedMultiplier());
        assertEquals(ParkingSpotStatus.AVAILABLE, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
        assertEquals(0, parkingSessionRepository.countByStatusIn(ACTIVE_STATUSES));
    }

    @Test
    void shouldPersistZeroAmountForThirtyMinuteStay() {
        prepareParkedSession("ZUL0001", 10, BigDecimal.TEN);

        exitService.processExit(exit("ZUL0001", ENTRY_TIME.plusSeconds(30 * 60)));

        ParkingSession session = parkingSessionRepository.findAll().getFirst();
        assertEquals(ParkingSessionStatus.EXITED, session.getStatus());
        assertEquals(new BigDecimal("0.00"), session.getAmount());
    }

    @Test
    void shouldKeepDuplicateExitIdempotent() {
        prepareParkedSession("ZUL0001", 10, BigDecimal.TEN);
        ExitEventRequest event = exit("ZUL0001", EXIT_TIME);
        exitService.processExit(event);
        ParkingSession original = parkingSessionRepository.findAll().getFirst();
        BigDecimal originalAmount = original.getAmount();
        Instant originalExitTime = original.getExitTime();

        exitService.processExit(event);

        ParkingSession duplicate = parkingSessionRepository.findAll().getFirst();
        assertEquals(1, parkingSessionRepository.count());
        assertEquals(originalAmount, duplicate.getAmount());
        assertEquals(originalExitTime, duplicate.getExitTime());
        assertEquals(ParkingSpotStatus.AVAILABLE, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
    }

    @Test
    void shouldTreatConcurrentDuplicateExitsAsSuccessful() throws Exception {
        prepareParkedSession("ZUL0001", 10, BigDecimal.TEN);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ExitOutcome> first = executor.submit(concurrentExit("ZUL0001", EXIT_TIME, ready, start));
            Future<ExitOutcome> second = executor.submit(concurrentExit("ZUL0001", EXIT_TIME, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(ExitOutcome.SUCCESS, first.get(10, TimeUnit.SECONDS));
            assertEquals(ExitOutcome.SUCCESS, second.get(10, TimeUnit.SECONDS));

            ParkingSession session = parkingSessionRepository.findAll().getFirst();
            assertEquals(1, parkingSessionRepository.count());
            assertEquals(ParkingSessionStatus.EXITED, session.getStatus());
            assertEquals(new BigDecimal("18.00"), session.getAmount());
            assertEquals(ParkingSpotStatus.AVAILABLE, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldSerializeExitAndEntryAtFullCapacity() throws Exception {
        prepareParkedSession("ZUL0001", 1, BigDecimal.TEN);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ExitOutcome> exit = executor.submit(concurrentExit("ZUL0001", EXIT_TIME, ready, start));
            Future<EntryOutcome> entry = executor.submit(concurrentEntry(
                    "ZUL0002", EXIT_TIME.plusSeconds(60), ready, start
            ));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(ExitOutcome.SUCCESS, exit.get(10, TimeUnit.SECONDS));
            EntryOutcome entryOutcome = entry.get(10, TimeUnit.SECONDS);
            long activeSessions = parkingSessionRepository.countByStatusIn(ACTIVE_STATUSES);

            assertTrue(activeSessions <= 1);
            if (entryOutcome == EntryOutcome.SUCCESS) {
                assertEquals(1, activeSessions);
                assertTrue(parkingSessionRepository.findByLicensePlateAndStatusIn("ZUL0002", ACTIVE_STATUSES)
                        .isPresent());
            } else {
                assertEquals(0, activeSessions);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldKeepSessionAndSpotConsistentWhenExitRacesWithParked() throws Exception {
        long sectorId = insertSector("A", 1, BigDecimal.TEN);
        insertSpot(1L, sectorId, LATITUDE, LONGITUDE);
        entryService.processEntry(entry("ZUL0001", ENTRY_TIME));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ParkedOutcome> parked = executor.submit(concurrentParked("ZUL0001", ready, start));
            Future<ExitOutcome> exit = executor.submit(concurrentExit("ZUL0001", EXIT_TIME, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(ParkedOutcome.SUCCESS, parked.get(10, TimeUnit.SECONDS));
            ExitOutcome exitOutcome = exit.get(10, TimeUnit.SECONDS);

            ParkingSession session = parkingSessionRepository.findAll().getFirst();
            ParkingSpotStatus spotStatus = parkingSpotRepository.findById(1L).orElseThrow().getStatus();
            if (exitOutcome == ExitOutcome.SUCCESS) {
                assertEquals(ParkingSessionStatus.EXITED, session.getStatus());
                assertEquals(ParkingSpotStatus.AVAILABLE, spotStatus);
            } else {
                assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
                assertEquals(ParkingSpotStatus.OCCUPIED, spotStatus);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldRollbackSessionWhenParkingSpotReleaseFails() {
        prepareParkedSession("ZUL0001", 10, BigDecimal.TEN);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_parking_spot_update
                BEFORE UPDATE ON parking_spot
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced parking spot update failure'
                """);

        try {
            assertThrows(DataAccessException.class, () -> exitService.processExit(exit("ZUL0001", EXIT_TIME)));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_parking_spot_update");
        }

        ParkingSession session = parkingSessionRepository.findAll().getFirst();
        assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
        assertNull(session.getExitTime());
        assertNull(session.getAmount());
        assertEquals(ParkingSpotStatus.OCCUPIED, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
    }

    @Test
    void shouldIgnoreOldDuplicateExitAfterNewEntry() {
        prepareParkedSession("ZUL0001", 2, BigDecimal.TEN);
        exitService.processExit(exit("ZUL0001", EXIT_TIME));
        Instant newEntryTime = EXIT_TIME.plusSeconds(60);
        entryService.processEntry(entry("ZUL0001", newEntryTime));

        exitService.processExit(exit("ZUL0001", EXIT_TIME));

        List<ParkingSession> sessions = parkingSessionRepository.findAll();
        assertEquals(2, sessions.size());
        assertEquals(1, sessions.stream().filter(item -> item.getStatus() == ParkingSessionStatus.EXITED).count());
        ParkingSession active = parkingSessionRepository
                .findByLicensePlateAndStatusIn("ZUL0001", ACTIVE_STATUSES)
                .orElseThrow();
        assertEquals(ParkingSessionStatus.ENTERED, active.getStatus());
        assertEquals(newEntryTime, active.getEntryTime());
    }

    @Test
    void shouldUseFrozenPriceAfterSectorPriceChanges() {
        long sectorId = prepareParkedSession("ZUL0001", 10, BigDecimal.TEN);
        jdbcTemplate.update("UPDATE sector SET base_price = ? WHERE id = ?", new BigDecimal("99.99"), sectorId);

        exitService.processExit(exit("ZUL0001", EXIT_TIME));

        ParkingSession session = parkingSessionRepository.findAll().getFirst();
        assertEquals(new BigDecimal("10.00"), session.getBasePriceAtParking());
        assertEquals(new BigDecimal("0.90"), session.getAppliedMultiplier());
        assertEquals(new BigDecimal("18.00"), session.getAmount());
    }

    @Test
    void shouldRejectLegacySessionWithoutMultiplierAndPreserveState() {
        prepareParkedSession("ZUL0001", 10, BigDecimal.TEN);
        jdbcTemplate.update("UPDATE parking_session SET applied_multiplier = NULL WHERE license_plate = ?", "ZUL0001");

        assertThrows(
                IncompleteParkingSessionPricingException.class,
                () -> exitService.processExit(exit("ZUL0001", EXIT_TIME))
        );

        ParkingSession session = parkingSessionRepository.findAll().getFirst();
        assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
        assertNull(session.getExitTime());
        assertNull(session.getAmount());
        assertEquals(ParkingSpotStatus.OCCUPIED, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
    }

    @Test
    void shouldAllowNewEntryAfterExitReleasesCapacity() {
        prepareParkedSession("ZUL0001", 1, BigDecimal.TEN);
        exitService.processExit(exit("ZUL0001", EXIT_TIME));

        entryService.processEntry(entry("ZUL0002", EXIT_TIME.plusSeconds(60)));

        assertEquals(2, parkingSessionRepository.count());
        assertEquals(1, parkingSessionRepository.countByStatusIn(ACTIVE_STATUSES));
        ParkingSession active = parkingSessionRepository
                .findByLicensePlateAndStatusIn("ZUL0002", ACTIVE_STATUSES)
                .orElseThrow();
        assertEquals(ParkingSessionStatus.ENTERED, active.getStatus());
    }

    private long prepareParkedSession(String plate, int capacity, BigDecimal basePrice) {
        long sectorId = insertSector("A", capacity, basePrice);
        insertSpot(1L, sectorId, LATITUDE, LONGITUDE);
        entryService.processEntry(entry(plate, ENTRY_TIME));
        assignmentService.processParked(parked(plate, LATITUDE, LONGITUDE));
        return sectorId;
    }

    private Callable<ExitOutcome> concurrentExit(
            String plate,
            Instant exitTime,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent EXIT start signal timed out");
            }
            try {
                exitService.processExit(exit(plate, exitTime));
                return ExitOutcome.SUCCESS;
            } catch (InvalidParkingSessionStateException exception) {
                return ExitOutcome.SEQUENCE_CONFLICT;
            }
        };
    }

    private Callable<ParkedOutcome> concurrentParked(
            String plate,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent PARKED start signal timed out");
            }
            assignmentService.processParked(parked(plate, LATITUDE, LONGITUDE));
            return ParkedOutcome.SUCCESS;
        };
    }

    private Callable<EntryOutcome> concurrentEntry(
            String plate,
            Instant entryTime,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent ENTRY start signal timed out");
            }
            try {
                entryService.processEntry(entry(plate, entryTime));
                return EntryOutcome.SUCCESS;
            } catch (GarageFullException exception) {
                return EntryOutcome.GARAGE_FULL;
            }
        };
    }

    private long insertSector(String code, int capacity, BigDecimal basePrice) {
        jdbcTemplate.update(
                "INSERT INTO sector (code, base_price, max_capacity) VALUES (?, ?, ?)",
                code,
                basePrice,
                capacity
        );
        return jdbcTemplate.queryForObject("SELECT id FROM sector WHERE code = ?", Long.class, code);
    }

    private void insertSpot(long id, long sectorId, BigDecimal latitude, BigDecimal longitude) {
        jdbcTemplate.update(
                "INSERT INTO parking_spot (id, sector_id, latitude, longitude, status) "
                        + "VALUES (?, ?, ?, ?, 'AVAILABLE')",
                id,
                sectorId,
                latitude,
                longitude
        );
    }

    private EntryEventRequest entry(String plate, Instant entryTime) {
        return new EntryEventRequest(plate, entryTime, WebhookEventType.ENTRY);
    }

    private ParkedEventRequest parked(String plate, BigDecimal latitude, BigDecimal longitude) {
        return new ParkedEventRequest(plate, latitude, longitude, WebhookEventType.PARKED);
    }

    private ExitEventRequest exit(String plate, Instant exitTime) {
        return new ExitEventRequest(plate, exitTime, WebhookEventType.EXIT);
    }

    private enum ExitOutcome {
        SUCCESS,
        SEQUENCE_CONFLICT
    }

    private enum ParkedOutcome {
        SUCCESS
    }

    private enum EntryOutcome {
        SUCCESS,
        GARAGE_FULL
    }
}
