package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.RevenueResponse;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.domain.model.Sector;
import br.com.estapar.parking.infrastructure.config.RevenueProperties;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.SectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevenueServiceTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-11-25T20:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private GarageInitializationState initializationState;
    private SectorRepository sectorRepository;
    private ParkingSessionRepository parkingSessionRepository;
    private RevenueService service;

    @BeforeEach
    void setUp() {
        initializationState = new GarageInitializationState();
        initializationState.markReady();
        sectorRepository = mock(SectorRepository.class);
        parkingSessionRepository = mock(ParkingSessionRepository.class);
        RevenueProperties properties = new RevenueProperties();
        properties.setZoneId(BUSINESS_ZONE);
        service = new RevenueService(
                initializationState,
                sectorRepository,
                parkingSessionRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties
        );
        when(sectorRepository.findByCode("A"))
                .thenReturn(Optional.of(new Sector("A", new BigDecimal("40.50"), 10)));
        when(sectorRepository.findByCode("B"))
                .thenReturn(Optional.of(new Sector("B", new BigDecimal("30.00"), 10)));
    }

    @Test
    void shouldReturnRevenueForSectorUsingPersistedDatabaseSum() {
        when(sum("A", Instant.parse("2025-01-01T03:00:00Z"), Instant.parse("2025-01-02T03:00:00Z")))
                .thenReturn(new BigDecimal("72.90"));

        RevenueResponse response = service.getRevenue(LocalDate.of(2025, 1, 1), "A");

        assertEquals(new BigDecimal("72.90"), response.amount());
    }

    @Test
    void shouldKeepRevenueIndependentBetweenSectors() {
        when(sum("B", Instant.parse("2025-01-01T03:00:00Z"), Instant.parse("2025-01-02T03:00:00Z")))
                .thenReturn(new BigDecimal("31.25"));

        RevenueResponse response = service.getRevenue(LocalDate.of(2025, 1, 1), " b ");

        assertEquals(new BigDecimal("31.25"), response.amount());
    }

    @Test
    void shouldReturnDatabaseAggregationOfMultipleExitedSessions() {
        when(sum("A", Instant.parse("2025-06-01T03:00:00Z"), Instant.parse("2025-06-02T03:00:00Z")))
                .thenReturn(new BigDecimal("123.456"));

        RevenueResponse response = service.getRevenue(LocalDate.of(2025, 6, 1), "A");

        assertEquals(new BigDecimal("123.46"), response.amount());
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,0.00", "0.00,0.00"}, nullValues = "NULL")
    void shouldNormalizeMissingOrZeroRevenue(String databaseAmount, String expected) {
        when(sum("A", Instant.parse("2025-01-01T03:00:00Z"), Instant.parse("2025-01-02T03:00:00Z")))
                .thenReturn(databaseAmount == null ? null : new BigDecimal(databaseAmount));

        RevenueResponse response = service.getRevenue(LocalDate.of(2025, 1, 1), "A");

        assertEquals(new BigDecimal(expected), response.amount());
        assertEquals(2, response.amount().scale());
    }

    @Test
    void shouldUseCurrentBusinessDateAndCurrentInstantAsUpperBoundWhenDateIsAbsent() {
        when(sum("A", Instant.parse("2026-11-25T03:00:00Z"), NOW)).thenReturn(BigDecimal.ONE);

        RevenueResponse response = service.getRevenue(null, "A");

        assertEquals(new BigDecimal("1.00"), response.amount());
        verify(parkingSessionRepository).sumAmountBySectorAndStatusAndExitTimeRange(
                "A",
                ParkingSessionStatus.EXITED,
                Instant.parse("2026-11-25T03:00:00Z"),
                NOW
        );
    }

    @Test
    void shouldUseFullBusinessDayForHistoricalDateAcrossYearBoundary() {
        when(sum("A", Instant.parse("2024-12-31T03:00:00Z"), Instant.parse("2025-01-01T03:00:00Z")))
                .thenReturn(BigDecimal.TEN);

        service.getRevenue(LocalDate.of(2024, 12, 31), "A");

        verify(parkingSessionRepository).sumAmountBySectorAndStatusAndExitTimeRange(
                "A",
                ParkingSessionStatus.EXITED,
                Instant.parse("2024-12-31T03:00:00Z"),
                Instant.parse("2025-01-01T03:00:00Z")
        );
    }

    @Test
    void shouldReturnZeroForFutureDateWithoutQueryingSessions() {
        RevenueResponse response = service.getRevenue(TODAY.plusDays(1), "A");

        assertEquals(new BigDecimal("0.00"), response.amount());
        verify(parkingSessionRepository, never()).sumAmountBySectorAndStatusAndExitTimeRange(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void shouldRejectMissingSector() {
        assertThrows(InvalidRevenueRequestException.class, () -> service.getRevenue(TODAY, "  "));
        verify(sectorRepository, never()).findByCode(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldReturnNotFoundForUnknownSector() {
        when(sectorRepository.findByCode("X")) .thenReturn(Optional.empty());

        assertThrows(SectorNotFoundException.class, () -> service.getRevenue(TODAY, "x"));
    }

    @Test
    void shouldRejectQueryWhileGarageIsNotReady() {
        initializationState = new GarageInitializationState();
        RevenueProperties properties = new RevenueProperties();
        service = new RevenueService(
                initializationState,
                sectorRepository,
                parkingSessionRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties
        );

        assertThrows(ParkingEventProcessingUnavailableException.class, () -> service.getRevenue(TODAY, "A"));
        verify(sectorRepository, never()).findByCode(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldAlwaysReturnBrlAndTimestampFromClock() {
        when(sum("A", Instant.parse("2025-01-01T03:00:00Z"), Instant.parse("2025-01-02T03:00:00Z")))
                .thenReturn(BigDecimal.ZERO);

        RevenueResponse response = service.getRevenue(LocalDate.of(2025, 1, 1), "A");

        assertEquals("BRL", response.currency());
        assertEquals(NOW, response.timestamp());
    }

    private BigDecimal sum(String sector, Instant startInclusive, Instant endExclusive) {
        return parkingSessionRepository.sumAmountBySectorAndStatusAndExitTimeRange(
                sector,
                ParkingSessionStatus.EXITED,
                startInclusive,
                endExclusive
        );
    }
}
