package br.com.estapar.parking.application;

import br.com.estapar.parking.ParkingApplication;
import br.com.estapar.parking.domain.model.ParkingSpotStatus;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSpotRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.SectorRepository;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageConfigurationResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSectorResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSpotResponse;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class GarageSyncServiceIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("estapar_parking")
            .withUsername("estapar")
            .withPassword("estapar");

    private static ConfigurableApplicationContext context;
    private static GarageSyncService syncService;
    private static SectorRepository sectorRepository;
    private static ParkingSpotRepository parkingSpotRepository;
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
        syncService = context.getBean(GarageSyncService.class);
        sectorRepository = context.getBean(SectorRepository.class);
        parkingSpotRepository = context.getBean(ParkingSpotRepository.class);
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
    }

    @Test
    void shouldPersistFirstLoadAndKeepSecondLoadIdempotent() {
        GarageConfigurationResponse configuration = configuration(
                new BigDecimal("10.00"),
                100,
                List.of(spot(1L, "-23.561684", "-46.655981"), spot(2L, "-23.561685", "-46.655982"))
        );

        GarageSyncResult first = syncService.synchronize(configuration);
        GarageSyncResult second = syncService.synchronize(configuration);

        assertEquals(1, first.sectorsInserted());
        assertEquals(2, first.spotsInserted());
        assertEquals(0, second.sectorsInserted());
        assertEquals(0, second.sectorsUpdated());
        assertEquals(0, second.spotsInserted());
        assertEquals(0, second.spotsUpdated());
        assertEquals(1, sectorRepository.count());
        assertEquals(2, parkingSpotRepository.count());
    }

    @Test
    void shouldUpdateBasePriceAndMaximumCapacity() {
        syncService.synchronize(configuration(new BigDecimal("10.00"), 100, List.of(spot(1L, "0", "0"))));

        GarageSyncResult result = syncService.synchronize(
                configuration(new BigDecimal("15.50"), 120, List.of(spot(1L, "0", "0")))
        );

        var sector = sectorRepository.findByCode("A").orElseThrow();
        assertEquals(0, sector.getBasePrice().compareTo(new BigDecimal("15.50")));
        assertEquals(120, sector.getMaxCapacity());
        assertEquals(1, result.sectorsUpdated());
    }

    @Test
    void shouldPreserveOccupiedSpotStatus() {
        GarageConfigurationResponse configuration = configuration(
                BigDecimal.TEN,
                1,
                List.of(spot(1L, "0", "0"))
        );
        syncService.synchronize(configuration);
        jdbcTemplate.update("UPDATE parking_spot SET status = 'OCCUPIED' WHERE id = 1");

        GarageSyncResult result = syncService.synchronize(configuration);

        assertEquals(0, result.spotsUpdated());
        assertEquals(ParkingSpotStatus.OCCUPIED, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
    }

    @Test
    void shouldRejectUnknownSectorAndDuplicateCoordinatesWithoutPersisting() {
        GarageConfigurationResponse unknownSector = new GarageConfigurationResponse(
                List.of(new GarageSectorResponse("A", BigDecimal.TEN, 1)),
                List.of(new GarageSpotResponse(1L, "B", BigDecimal.ZERO, BigDecimal.ZERO))
        );
        GarageConfigurationResponse duplicateCoordinates = configuration(
                BigDecimal.TEN,
                2,
                List.of(spot(1L, "0", "0"), spot(2L, "0.0", "0.00"))
        );

        assertThrows(InvalidGarageConfigurationException.class, () -> syncService.synchronize(unknownSector));
        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> syncService.synchronize(duplicateCoordinates)
        );
        assertEquals(0, sectorRepository.count());
        assertEquals(0, parkingSpotRepository.count());
    }

    @Test
    void shouldRollbackSectorUpdateWhenSpotSynchronizationConflicts() {
        syncService.synchronize(configuration(BigDecimal.TEN, 1, List.of(spot(1L, "0", "0"))));
        jdbcTemplate.update("UPDATE parking_spot SET status = 'OCCUPIED' WHERE id = 1");

        GarageConfigurationResponse conflicting = configuration(
                new BigDecimal("20.00"),
                2,
                List.of(spot(1L, "1", "1"))
        );

        assertThrows(GarageSyncConflictException.class, () -> syncService.synchronize(conflicting));
        assertEquals(
                0,
                sectorRepository.findByCode("A").orElseThrow().getBasePrice().compareTo(BigDecimal.TEN)
        );
        assertEquals(ParkingSpotStatus.OCCUPIED, parkingSpotRepository.findById(1L).orElseThrow().getStatus());
    }

    @Test
    void shouldRejectGlobalCapacityReductionBelowActiveSessions() {
        syncService.synchronize(configuration(
                BigDecimal.TEN,
                2,
                List.of(spot(1L, "0", "0"), spot(2L, "1", "1"))
        ));
        insertEnteredSession("ZUL0001");
        insertEnteredSession("ZUL0002");

        GarageConfigurationResponse reducedCapacity = configuration(
                new BigDecimal("20.00"),
                1,
                List.of(spot(1L, "0", "0"), spot(2L, "1", "1"))
        );

        assertThrows(GarageSyncConflictException.class, () -> syncService.synchronize(reducedCapacity));
        var sector = sectorRepository.findByCode("A").orElseThrow();
        assertEquals(2, sector.getMaxCapacity());
        assertEquals(0, sector.getBasePrice().compareTo(BigDecimal.TEN));
    }

    @Test
    void shouldRejectSectorCapacityReductionBelowParkedSessions() {
        syncService.synchronize(configuration(
                BigDecimal.TEN,
                2,
                List.of(spot(1L, "0", "0"), spot(2L, "1", "1"))
        ));
        jdbcTemplate.update(
                "INSERT INTO sector (code, base_price, max_capacity) VALUES ('B', 10.00, 10)"
        );
        long sectorId = sectorRepository.findByCode("A").orElseThrow().getId();
        insertParkedSession("ZUL0001", sectorId, 1L);
        insertParkedSession("ZUL0002", sectorId, 2L);

        GarageConfigurationResponse reducedCapacity = configuration(
                new BigDecimal("20.00"),
                1,
                List.of(spot(1L, "0", "0"), spot(2L, "1", "1"))
        );

        assertThrows(GarageSyncConflictException.class, () -> syncService.synchronize(reducedCapacity));
        var sector = sectorRepository.findByCode("A").orElseThrow();
        assertEquals(2, sector.getMaxCapacity());
        assertEquals(0, sector.getBasePrice().compareTo(BigDecimal.TEN));
    }

    private void insertEnteredSession(String licensePlate) {
        jdbcTemplate.update(
                "INSERT INTO parking_session (license_plate, status, entry_time) VALUES (?, 'ENTERED', ?)",
                licensePlate,
                LocalDateTime.of(2025, 1, 1, 12, 0)
        );
    }

    private void insertParkedSession(String licensePlate, long sectorId, long spotId) {
        jdbcTemplate.update("UPDATE parking_spot SET status = 'OCCUPIED' WHERE id = ?", spotId);
        jdbcTemplate.update(
                """
                        INSERT INTO parking_session
                            (license_plate, status, entry_time, parked_at, parking_spot_id, sector_id,
                             base_price_at_parking, applied_multiplier)
                        VALUES (?, 'PARKED', ?, ?, ?, ?, 10.00, 1.00)
                        """,
                licensePlate,
                LocalDateTime.of(2025, 1, 1, 12, 0),
                LocalDateTime.of(2025, 1, 1, 12, 1),
                spotId,
                sectorId
        );
    }

    private static GarageConfigurationResponse configuration(
            BigDecimal basePrice,
            int maxCapacity,
            List<GarageSpotResponse> spots
    ) {
        return new GarageConfigurationResponse(
                List.of(new GarageSectorResponse("A", basePrice, maxCapacity)),
                spots
        );
    }

    private static GarageSpotResponse spot(long id, String latitude, String longitude) {
        return new GarageSpotResponse(
                id,
                "A",
                new BigDecimal(latitude),
                new BigDecimal(longitude)
        );
    }
}
