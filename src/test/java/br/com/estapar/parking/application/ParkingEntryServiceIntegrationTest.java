package br.com.estapar.parking.application;

import br.com.estapar.parking.ParkingApplication;
import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventType;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
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
class ParkingEntryServiceIntegrationTest {

    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");
    private static final EnumSet<ParkingSessionStatus> ACTIVE_STATUSES =
            EnumSet.of(ParkingSessionStatus.ENTERED, ParkingSessionStatus.PARKED);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("estapar_parking")
            .withUsername("estapar")
            .withPassword("estapar");

    private static ConfigurableApplicationContext context;
    private static ParkingEntryService service;
    private static ParkingSessionRepository parkingSessionRepository;
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
        service = context.getBean(ParkingEntryService.class);
        parkingSessionRepository = context.getBean(ParkingSessionRepository.class);
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
        jdbcTemplate.update("DELETE FROM parking_session");
        jdbcTemplate.update("DELETE FROM parking_spot");
        jdbcTemplate.update("DELETE FROM sector");
        initializationState.markReady();
    }

    @Test
    void shouldPersistRealEntryWithoutPhysicalAssociations() {
        insertSector("A", 10);

        service.processEntry(event("ZUL0001", ENTRY_TIME));

        var session = parkingSessionRepository.findAll().getFirst();
        assertEquals("ZUL0001", session.getLicensePlate());
        assertEquals(ENTRY_TIME, session.getEntryTime());
        assertEquals(ParkingSessionStatus.ENTERED, session.getStatus());
        assertNull(session.getParkingSpot());
        assertNull(session.getSector());
        assertNull(session.getParkedAt());
        assertNull(session.getExitTime());
        assertNull(session.getAmount());
    }

    @Test
    void shouldRollbackWhenPersistenceFails() {
        insertSector("A", 10);
        String plateLongerThanDatabaseColumn = "PLATE-LONGER-THAN-20-CHARS";

        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.processEntry(event(plateLongerThanDatabaseColumn, ENTRY_TIME))
        );

        assertEquals(0, parkingSessionRepository.count());
    }

    @Test
    void shouldEnforceActiveLicensePlateUniqueConstraint() {
        insertSector("A", 10);
        insertSession("ZUL0001", "ENTERED", ENTRY_TIME, null);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSession("ZUL0001", "PARKED", ENTRY_TIME.plusSeconds(1), null)
        );
        assertEquals(1, parkingSessionRepository.count());
    }

    @Test
    void shouldAllowEntryAfterExitedSession() {
        insertSector("A", 10);
        insertSession("ZUL0001", "EXITED", ENTRY_TIME.minusSeconds(3600), ENTRY_TIME.minusSeconds(60));

        service.processEntry(event("ZUL0001", ENTRY_TIME));

        assertEquals(2, parkingSessionRepository.count());
        assertEquals(1, parkingSessionRepository.countByStatusIn(ACTIVE_STATUSES));
    }

    @Test
    void shouldSerializeConcurrentEntriesAtLastAvailableCapacity() throws Exception {
        insertSector("A", 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Outcome> first = executor.submit(concurrentEntry(
                    event("ZUL0001", ENTRY_TIME),
                    ready,
                    start
            ));
            Future<Outcome> second = executor.submit(concurrentEntry(
                    event("ZUL0002", ENTRY_TIME),
                    ready,
                    start
            ));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertEquals(1, outcomes.stream().filter(Outcome.SUCCESS::equals).count());
            assertEquals(1, outcomes.stream().filter(Outcome.GARAGE_FULL::equals).count());
            assertEquals(1, parkingSessionRepository.countByStatusIn(ACTIVE_STATUSES));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldTreatConcurrentDuplicateEntriesAsIdempotent() throws Exception {
        insertSector("A", 1);
        EntryEventRequest duplicate = event("ZUL0001", ENTRY_TIME);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Outcome> first = executor.submit(concurrentEntry(duplicate, ready, start));
            Future<Outcome> second = executor.submit(concurrentEntry(duplicate, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Outcome.SUCCESS, first.get(10, TimeUnit.SECONDS));
            assertEquals(Outcome.SUCCESS, second.get(10, TimeUnit.SECONDS));
            assertEquals(1, parkingSessionRepository.count());
            assertEquals(1, parkingSessionRepository.countByStatusIn(ACTIVE_STATUSES));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Callable<Outcome> concurrentEntry(
            EntryEventRequest event,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent entry start signal timed out");
            }
            try {
                service.processEntry(event);
                return Outcome.SUCCESS;
            } catch (GarageFullException exception) {
                return Outcome.GARAGE_FULL;
            }
        };
    }

    private void insertSector(String code, int capacity) {
        jdbcTemplate.update(
                "INSERT INTO sector (code, base_price, max_capacity) VALUES (?, ?, ?)",
                code,
                BigDecimal.TEN,
                capacity
        );
    }

    private void insertSession(String plate, String status, Instant entryTime, Instant exitTime) {
        jdbcTemplate.update(
                "INSERT INTO parking_session (license_plate, status, entry_time, exit_time) VALUES (?, ?, ?, ?)",
                plate,
                status,
                entryTime,
                exitTime
        );
    }

    private EntryEventRequest event(String plate, Instant entryTime) {
        return new EntryEventRequest(plate, entryTime, WebhookEventType.ENTRY);
    }

    private enum Outcome {
        SUCCESS,
        GARAGE_FULL
    }
}
