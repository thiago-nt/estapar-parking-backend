package br.com.estapar.parking.application;

import br.com.estapar.parking.ParkingApplication;
import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class ParkingSpotAssignmentServiceIntegrationTest {

    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");
    private static final BigDecimal LATITUDE = new BigDecimal("-23.5616840");
    private static final BigDecimal LONGITUDE = new BigDecimal("-46.6559810");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("estapar_parking")
            .withUsername("estapar")
            .withPassword("estapar")
            .withCommand("--log-bin-trust-function-creators=1");

    private static ConfigurableApplicationContext context;
    private static ParkingEntryService entryService;
    private static ParkingSpotAssignmentService assignmentService;
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
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_parking_session_update");
        jdbcTemplate.update("DELETE FROM parking_session");
        jdbcTemplate.update("DELETE FROM parking_spot");
        jdbcTemplate.update("DELETE FROM sector");
        initializationState.markReady();
    }

    @Test
    void shouldPersistEntryToParkedWorkflow() {
        long sectorId = insertSector("A", 10, new BigDecimal("40.50"));
        insertSpot(1L, sectorId, LATITUDE, LONGITUDE);
        entryService.processEntry(entry("ZUL0001", ENTRY_TIME));

        assignmentService.processParked(parked("ZUL0001", LATITUDE, LONGITUDE));

        var session = parkingSessionRepository.findAll().getFirst();
        var spot = parkingSpotRepository.findById(1L).orElseThrow();
        assertEquals(1, parkingSessionRepository.count());
        assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
        assertEquals(ENTRY_TIME, session.getEntryTime());
        assertNotNull(session.getParkedAt());
        assertEquals(1L, session.getParkingSpot().getId());
        assertEquals(sectorId, session.getSector().getId());
        assertEquals(0, session.getBasePriceAtParking().compareTo(new BigDecimal("40.50")));
        assertNull(session.getAppliedMultiplier());
        assertNull(session.getAmount());
        assertEquals(ParkingSpotStatus.OCCUPIED, spot.getStatus());
    }

    @Test
    void shouldKeepDuplicateParkedIdempotent() {
        long sectorId = insertSector("A", 10, BigDecimal.TEN);
        insertSpot(1L, sectorId, LATITUDE, LONGITUDE);
        entryService.processEntry(entry("ZUL0001", ENTRY_TIME));
        ParkedEventRequest event = parked("ZUL0001", LATITUDE, LONGITUDE);
        assignmentService.processParked(event);
        Instant originalParkedAt = parkingSessionRepository.findAll().getFirst().getParkedAt();

        assignmentService.processParked(event);

        var session = parkingSessionRepository.findAll().getFirst();
        assertEquals(1, parkingSessionRepository.count());
        assertEquals(originalParkedAt, session.getParkedAt());
        assertEquals(1L, session.getParkingSpot().getId());
    }

    @Test
    void shouldSerializeConcurrentAssignmentsToSameSpot() throws Exception {
        long sectorId = insertSector("A", 2, BigDecimal.TEN);
        insertSpot(1L, sectorId, LATITUDE, LONGITUDE);
        entryService.processEntry(entry("ZUL0001", ENTRY_TIME));
        entryService.processEntry(entry("ZUL0002", ENTRY_TIME));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Outcome> first = executor.submit(concurrentParked("ZUL0001", ready, start));
            Future<Outcome> second = executor.submit(concurrentParked("ZUL0002", ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertEquals(1, outcomes.stream().filter(Outcome.SUCCESS::equals).count());
            assertEquals(1, outcomes.stream().filter(Outcome.SPOT_OCCUPIED::equals).count());
            assertEquals(1, parkingSessionRepository.countBySectorIdAndStatus(
                    sectorId,
                    ParkingSessionStatus.PARKED
            ));
            assertEquals(ParkingSpotStatus.OCCUPIED, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldRollbackSpotAndSessionWhenPersistenceFailsAfterStateChanges() {
        long sectorId = insertSector("A", 10, BigDecimal.TEN);
        insertSpot(1L, sectorId, LATITUDE, LONGITUDE);
        entryService.processEntry(entry("ZUL0001", ENTRY_TIME));
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_parking_session_update
                BEFORE UPDATE ON parking_session
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced parking session update failure'
                """);

        try {
            assertThrows(
                    DataAccessException.class,
                    () -> assignmentService.processParked(parked("ZUL0001", LATITUDE, LONGITUDE))
            );
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_parking_session_update");
        }

        var session = parkingSessionRepository.findAll().getFirst();
        var spot = parkingSpotRepository.findById(1L).orElseThrow();
        assertEquals(ParkingSessionStatus.ENTERED, session.getStatus());
        assertNull(session.getParkingSpot());
        assertNull(session.getSector());
        assertNull(session.getParkedAt());
        assertEquals(ParkingSpotStatus.AVAILABLE, spot.getStatus());
    }

    @Test
    void shouldRejectMovingParkedSessionToAnotherSpot() {
        long sectorId = insertSector("A", 10, BigDecimal.TEN);
        insertSpot(1L, sectorId, LATITUDE, LONGITUDE);
        BigDecimal otherLatitude = new BigDecimal("-23.5616850");
        BigDecimal otherLongitude = new BigDecimal("-46.6559820");
        insertSpot(2L, sectorId, otherLatitude, otherLongitude);
        entryService.processEntry(entry("ZUL0001", ENTRY_TIME));
        assignmentService.processParked(parked("ZUL0001", LATITUDE, LONGITUDE));

        assertThrows(
                InvalidParkingSessionStateException.class,
                () -> assignmentService.processParked(parked("ZUL0001", otherLatitude, otherLongitude))
        );
        assertEquals(ParkingSpotStatus.AVAILABLE, parkingSpotRepository.findById(2L).orElseThrow().getStatus());
    }

    @Test
    void shouldEnforceSectorPhysicalCapacityWithoutChangingGlobalOccupancy() {
        long sectorA = insertSector("A", 1, BigDecimal.TEN);
        insertSector("B", 1, BigDecimal.TEN);
        insertSpot(1L, sectorA, LATITUDE, LONGITUDE);
        BigDecimal otherLatitude = new BigDecimal("-23.5616850");
        BigDecimal otherLongitude = new BigDecimal("-46.6559820");
        insertSpot(2L, sectorA, otherLatitude, otherLongitude);
        entryService.processEntry(entry("ZUL0001", ENTRY_TIME));
        entryService.processEntry(entry("ZUL0002", ENTRY_TIME));
        assignmentService.processParked(parked("ZUL0001", LATITUDE, LONGITUDE));

        assertThrows(
                SectorCapacityExceededException.class,
                () -> assignmentService.processParked(parked("ZUL0002", otherLatitude, otherLongitude))
        );
        assertEquals(2L, parkingSessionRepository.countByStatusIn(
                List.of(ParkingSessionStatus.ENTERED, ParkingSessionStatus.PARKED)
        ));
        assertEquals(ParkingSpotStatus.AVAILABLE, parkingSpotRepository.findById(2L).orElseThrow().getStatus());
    }

    private Callable<Outcome> concurrentParked(String plate, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent PARKED start signal timed out");
            }
            try {
                assignmentService.processParked(parked(plate, LATITUDE, LONGITUDE));
                return Outcome.SUCCESS;
            } catch (ParkingSpotOccupiedException exception) {
                return Outcome.SPOT_OCCUPIED;
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

    private enum Outcome {
        SUCCESS,
        SPOT_OCCUPIED
    }
}
