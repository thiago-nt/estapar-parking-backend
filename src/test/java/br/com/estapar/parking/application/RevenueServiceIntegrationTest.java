package br.com.estapar.parking.application;

import br.com.estapar.parking.ParkingApplication;
import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.api.dto.RevenueResponse;
import br.com.estapar.parking.api.dto.WebhookEventType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RevenueServiceIntegrationTest {

    private static final BigDecimal LATITUDE = new BigDecimal("-23.5616840");
    private static final BigDecimal LONGITUDE = new BigDecimal("-46.6559810");
    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");
    private static final Instant EXIT_TIME = Instant.parse("2025-01-01T13:30:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("estapar_parking")
            .withUsername("estapar")
            .withPassword("estapar");

    private static ConfigurableApplicationContext context;
    private static RevenueService revenueService;
    private static ParkingEntryService entryService;
    private static ParkingSpotAssignmentService assignmentService;
    private static ParkingExitService exitService;
    private static GarageInitializationState initializationState;
    private static JdbcTemplate jdbcTemplate;
    private static DataSource dataSource;

    @BeforeAll
    static void startApplicationContext() {
        context = new SpringApplicationBuilder(ParkingApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--garage.simulator.enabled=false",
                        "--revenue.zone-id=America/Sao_Paulo",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword()
                );
        revenueService = context.getBean(RevenueService.class);
        entryService = context.getBean(ParkingEntryService.class);
        assignmentService = context.getBean(ParkingSpotAssignmentService.class);
        exitService = context.getBean(ParkingExitService.class);
        initializationState = context.getBean(GarageInitializationState.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        dataSource = context.getBean(DataSource.class);
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
    void shouldReportRevenueAfterCompleteEntryParkedExitFlow() {
        prepareGarage("A", BigDecimal.TEN, 10);

        completeSession("ZUL1001", ENTRY_TIME, EXIT_TIME);

        assertRevenue(LocalDate.of(2025, 1, 1), "A", "18.00");
    }

    @Test
    void shouldSumMultipleExitedSessionsAndIgnoreActiveStatuses() {
        long sectorId = insertSector("A", BigDecimal.TEN, 10);
        insertExitedSession("ZUL2001", sectorId, EXIT_TIME, "10.10");
        insertExitedSession("ZUL2002", sectorId, EXIT_TIME.plusSeconds(60), "20.20");
        insertExitedSession("ZUL2003", sectorId, EXIT_TIME.plusSeconds(120), "30.30");
        insertActiveSession("ZUL2004", sectorId, "ENTERED", ENTRY_TIME);
        insertActiveSession("ZUL2005", sectorId, "PARKED", ENTRY_TIME);

        assertRevenue(LocalDate.of(2025, 1, 1), "A", "60.60");
    }

    @Test
    void shouldKeepSectorRevenueIndependent() {
        long sectorA = insertSector("A", BigDecimal.TEN, 10);
        long sectorB = insertSector("B", BigDecimal.TEN, 10);
        insertExitedSession("ZUL3001", sectorA, EXIT_TIME, "12.34");
        insertExitedSession("ZUL3002", sectorB, EXIT_TIME, "98.76");

        assertRevenue(LocalDate.of(2025, 1, 1), "A", "12.34");
        assertRevenue(LocalDate.of(2025, 1, 1), "B", "98.76");
    }

    @Test
    void shouldAssignRevenueToExitDateInsteadOfEntryDate() {
        long sectorId = insertSector("A", BigDecimal.TEN, 10);
        insertExitedSession(
                "ZUL4001",
                sectorId,
                Instant.parse("2025-01-02T04:00:00Z"),
                "15.00",
                Instant.parse("2025-01-01T02:30:00Z")
        );

        assertRevenue(LocalDate.of(2025, 1, 1), "A", "0.00");
        assertRevenue(LocalDate.of(2025, 1, 2), "A", "15.00");
    }

    @Test
    void shouldHandleMonthAndYearBoundaries() {
        long sectorId = insertSector("A", BigDecimal.TEN, 10);
        insertExitedSession("ZUL5001", sectorId, Instant.parse("2025-01-01T02:59:59Z"), "11.00");
        insertExitedSession("ZUL5002", sectorId, Instant.parse("2025-01-01T03:00:00Z"), "22.00");
        insertExitedSession("ZUL5003", sectorId, Instant.parse("2025-02-01T03:00:00Z"), "33.00");

        assertRevenue(LocalDate.of(2024, 12, 31), "A", "11.00");
        assertRevenue(LocalDate.of(2025, 1, 1), "A", "22.00");
        assertRevenue(LocalDate.of(2025, 2, 1), "A", "33.00");
    }

    @Test
    void shouldUseSaoPauloBusinessTimezoneAroundLocalMidnight() {
        long sectorId = insertSector("A", BigDecimal.TEN, 10);
        insertExitedSession("ZUL6001", sectorId, Instant.parse("2025-06-10T02:59:59.999999Z"), "14.00");
        insertExitedSession("ZUL6002", sectorId, Instant.parse("2025-06-10T03:00:00Z"), "28.00");

        assertRevenue(LocalDate.of(2025, 6, 9), "A", "14.00");
        assertRevenue(LocalDate.of(2025, 6, 10), "A", "28.00");
    }

    @Test
    void shouldIncludeStartAndExcludeNextDayStart() {
        long sectorId = insertSector("A", BigDecimal.TEN, 10);
        insertExitedSession("ZUL7001", sectorId, Instant.parse("2025-03-15T03:00:00Z"), "17.00");
        insertExitedSession("ZUL7002", sectorId, Instant.parse("2025-03-16T03:00:00Z"), "34.00");

        assertRevenue(LocalDate.of(2025, 3, 15), "A", "17.00");
    }

    @Test
    void shouldNotDuplicateRevenueWhenExitIsRedelivered() {
        prepareGarage("A", BigDecimal.TEN, 10);
        completeSession("ZUL8001", ENTRY_TIME, EXIT_TIME);

        exitService.processExit(exit("ZUL8001", EXIT_TIME));

        assertRevenue(LocalDate.of(2025, 1, 1), "A", "18.00");
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM parking_session", Long.class));
    }

    @Test
    void shouldOnlyObserveCommittedExitDuringConcurrentClose() throws Exception {
        long sectorId = insertSector("A", BigDecimal.TEN, 10);
        insertActiveSession("ZUL9001", sectorId, "PARKED", ENTRY_TIME);
        CountDownLatch updateExecuted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Void> closingTransaction = executor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("""
                            UPDATE parking_session
                            SET status = 'EXITED', exit_time = ?, amount = ?
                            WHERE license_plate = ?
                            """)) {
                        statement.setObject(1, utcDateTime(EXIT_TIME));
                        statement.setBigDecimal(2, new BigDecimal("45.67"));
                        statement.setString(3, "ZUL9001");
                        assertEquals(1, statement.executeUpdate());
                    }
                    updateExecuted.countDown();
                    if (!allowCommit.await(10, TimeUnit.SECONDS)) {
                        connection.rollback();
                        throw new IllegalStateException("Commit signal timed out");
                    }
                    connection.commit();
                }
                return null;
            });

            assertTrue(updateExecuted.await(10, TimeUnit.SECONDS));
            assertRevenue(LocalDate.of(2025, 1, 1), "A", "0.00");
            allowCommit.countDown();
            closingTransaction.get(10, TimeUnit.SECONDS);
            assertRevenue(LocalDate.of(2025, 1, 1), "A", "45.67");
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldKeepRevenueFrozenAfterSectorPriceChanges() {
        prepareGarage("A", BigDecimal.TEN, 10);
        completeSession("ZULA001", ENTRY_TIME, EXIT_TIME);
        jdbcTemplate.update("UPDATE sector SET base_price = ? WHERE code = 'A'", new BigDecimal("999.99"));

        assertRevenue(LocalDate.of(2025, 1, 1), "A", "18.00");
    }

    private void prepareGarage(String sectorCode, BigDecimal basePrice, int capacity) {
        long sectorId = insertSector(sectorCode, basePrice, capacity);
        jdbcTemplate.update(
                "INSERT INTO parking_spot (id, sector_id, latitude, longitude, status) "
                        + "VALUES (1, ?, ?, ?, 'AVAILABLE')",
                sectorId,
                LATITUDE,
                LONGITUDE
        );
    }

    private void completeSession(String plate, Instant entryTime, Instant exitTime) {
        entryService.processEntry(entry(plate, entryTime));
        assignmentService.processParked(parked(plate));
        exitService.processExit(exit(plate, exitTime));
    }

    private long insertSector(String code, BigDecimal basePrice, int capacity) {
        jdbcTemplate.update(
                "INSERT INTO sector (code, base_price, max_capacity) VALUES (?, ?, ?)",
                code,
                basePrice,
                capacity
        );
        return jdbcTemplate.queryForObject("SELECT id FROM sector WHERE code = ?", Long.class, code);
    }

    private void insertExitedSession(String plate, long sectorId, Instant exitTime, String amount) {
        insertExitedSession(plate, sectorId, exitTime, amount, exitTime.minusSeconds(3600));
    }

    private void insertExitedSession(
            String plate,
            long sectorId,
            Instant exitTime,
            String amount,
            Instant entryTime
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO parking_session
                            (license_plate, status, entry_time, exit_time, sector_id,
                             base_price_at_parking, applied_multiplier, amount)
                        VALUES (?, 'EXITED', ?, ?, ?, 10.00, 1.00, ?)
                        """,
                plate,
                utcDateTime(entryTime),
                utcDateTime(exitTime),
                sectorId,
                new BigDecimal(amount)
        );
    }

    private void insertActiveSession(String plate, long sectorId, String status, Instant entryTime) {
        jdbcTemplate.update(
                "INSERT INTO parking_session (license_plate, status, entry_time, sector_id) VALUES (?, ?, ?, ?)",
                plate,
                status,
                utcDateTime(entryTime),
                sectorId
        );
    }

    private void assertRevenue(LocalDate date, String sector, String expectedAmount) {
        RevenueResponse response = revenueService.getRevenue(date, sector);
        assertEquals(new BigDecimal(expectedAmount), response.amount());
        assertEquals("BRL", response.currency());
    }

    private static LocalDateTime utcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private EntryEventRequest entry(String plate, Instant time) {
        return new EntryEventRequest(plate, time, WebhookEventType.ENTRY);
    }

    private ParkedEventRequest parked(String plate) {
        return new ParkedEventRequest(plate, LATITUDE, LONGITUDE, WebhookEventType.PARKED);
    }

    private ExitEventRequest exit(String plate, Instant time) {
        return new ExitEventRequest(plate, time, WebhookEventType.EXIT);
    }
}
