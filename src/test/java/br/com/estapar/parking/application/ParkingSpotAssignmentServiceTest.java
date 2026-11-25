package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventType;
import br.com.estapar.parking.domain.model.GarageState;
import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.domain.model.ParkingSpot;
import br.com.estapar.parking.domain.model.ParkingSpotStatus;
import br.com.estapar.parking.domain.model.Sector;
import br.com.estapar.parking.infrastructure.persistence.repository.GarageStateRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSpotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParkingSpotAssignmentServiceTest {

    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");
    private static final Instant PARKED_AT = Instant.parse("2025-01-01T12:15:00Z");
    private static final BigDecimal LATITUDE = new BigDecimal("-23.5616840");
    private static final BigDecimal LONGITUDE = new BigDecimal("-46.6559810");

    private GarageInitializationState initializationState;
    private GarageStateRepository garageStateRepository;
    private ParkingSessionRepository parkingSessionRepository;
    private ParkingSpotRepository parkingSpotRepository;
    private ParkingSession session;
    private Sector sector;
    private ParkingSpot spot;
    private ParkingSpotAssignmentService service;

    @BeforeEach
    void setUp() {
        initializationState = new GarageInitializationState();
        initializationState.markReady();
        garageStateRepository = mock(GarageStateRepository.class);
        parkingSessionRepository = mock(ParkingSessionRepository.class);
        parkingSpotRepository = mock(ParkingSpotRepository.class);
        Clock clock = Clock.fixed(PARKED_AT, ZoneOffset.UTC);
        service = new ParkingSpotAssignmentService(
                initializationState,
                garageStateRepository,
                parkingSessionRepository,
                parkingSpotRepository,
                clock
        );

        session = new ParkingSession("ZUL0001", ENTRY_TIME);
        sector = new Sector("A", new BigDecimal("40.50"), 10);
        spot = new ParkingSpot(1L, sector, LATITUDE, LONGITUDE);

        when(garageStateRepository.findControlStateForUpdate()).thenReturn(Optional.of(mock(GarageState.class)));
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.of(session));
        when(parkingSpotRepository.findByLatitudeAndLongitude(LATITUDE, LONGITUDE))
                .thenReturn(Optional.of(spot));
        when(parkingSessionRepository.countBySectorIdAndStatus(any(), any())).thenReturn(0L);
    }

    @Test
    void shouldAssociateParkingSpotForValidParkedEvent() {
        service.processParked(event());

        assertEquals(spot, session.getParkingSpot());
        verify(parkingSessionRepository).saveAndFlush(session);
    }

    @Test
    void shouldChangeSessionStatusToParked() {
        service.processParked(event());

        assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
        assertEquals(PARKED_AT, session.getParkedAt());
    }

    @Test
    void shouldOccupyParkingSpot() {
        service.processParked(event());

        assertEquals(ParkingSpotStatus.OCCUPIED, spot.getStatus());
    }

    @Test
    void shouldAssociateSpotSector() {
        service.processParked(event());

        assertEquals(sector, session.getSector());
    }

    @Test
    void shouldCaptureSectorBasePriceWithoutCalculatingAmount() {
        service.processParked(event());

        assertEquals(0, session.getBasePriceAtParking().compareTo(new BigDecimal("40.50")));
        assertNull(session.getAppliedMultiplier());
        assertNull(session.getAmount());
    }

    @Test
    void shouldPreserveEntryTime() {
        service.processParked(event());

        assertEquals(ENTRY_TIME, session.getEntryTime());
    }

    @Test
    void shouldTreatDuplicateParkedAsIdempotent() {
        session.assignParkingSpot(spot, PARKED_AT.minusSeconds(30));
        spot.occupy();

        assertDoesNotThrow(() -> service.processParked(event()));
        verify(parkingSessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldNotChangeParkedAtOrPriceOnDuplicate() {
        Instant originalParkedAt = PARKED_AT.minusSeconds(30);
        session.assignParkingSpot(spot, originalParkedAt);
        spot.occupy();
        BigDecimal originalPrice = session.getBasePriceAtParking();

        service.processParked(event());

        assertEquals(originalParkedAt, session.getParkedAt());
        assertEquals(originalPrice, session.getBasePriceAtParking());
    }

    @Test
    void shouldRejectPlateWithoutActiveSession() {
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.empty());

        assertThrows(ParkingSessionNotFoundException.class, () -> service.processParked(event()));
    }

    @Test
    void shouldReturnNotFoundForUnknownCoordinates() {
        when(parkingSpotRepository.findByLatitudeAndLongitude(LATITUDE, LONGITUDE))
                .thenReturn(Optional.empty());

        assertThrows(ParkingSpotNotFoundException.class, () -> service.processParked(event()));
    }

    @Test
    void shouldRejectOccupiedSpot() {
        spot.occupy();

        assertThrows(ParkingSpotOccupiedException.class, () -> service.processParked(event()));
    }

    @Test
    void shouldRejectParkedSessionAtDifferentSpot() {
        ParkingSpot otherSpot = new ParkingSpot(
                2L,
                sector,
                new BigDecimal("-23.5616850"),
                new BigDecimal("-46.6559820")
        );
        session.assignParkingSpot(otherSpot, PARKED_AT.minusSeconds(30));
        otherSpot.occupy();

        assertThrows(InvalidParkingSessionStateException.class, () -> service.processParked(event()));
    }

    @Test
    void shouldRejectExitedSessionSequenceAsNoActiveSession() {
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.empty());

        assertThrows(ParkingSessionNotFoundException.class, () -> service.processParked(event()));
        verify(parkingSessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldNotIncreaseGlobalActiveOccupancy() {
        service.processParked(event());

        verify(parkingSessionRepository, never()).countByStatusIn(anyCollection());
        assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
    }

    @Test
    void shouldRejectAssignmentWhenSectorPhysicalCapacityIsReached() {
        when(parkingSessionRepository.countBySectorIdAndStatus(any(), any())).thenReturn(10L);

        assertThrows(SectorCapacityExceededException.class, () -> service.processParked(event()));
        assertEquals(ParkingSpotStatus.AVAILABLE, spot.getStatus());
    }

    @Test
    void shouldRejectParkedWhenGarageIsNotReady() {
        ParkingSpotAssignmentService pendingService = new ParkingSpotAssignmentService(
                new GarageInitializationState(),
                garageStateRepository,
                parkingSessionRepository,
                parkingSpotRepository,
                Clock.fixed(PARKED_AT, ZoneOffset.UTC)
        );

        assertThrows(ParkingEventProcessingUnavailableException.class, () -> pendingService.processParked(event()));
        verify(garageStateRepository, never()).findControlStateForUpdate();
    }

    @Test
    void shouldLookupSpotByExactPersistedCoordinates() {
        service.processParked(event());

        verify(parkingSpotRepository).findByLatitudeAndLongitude(LATITUDE, LONGITUDE);
    }

    private ParkedEventRequest event() {
        return new ParkedEventRequest("ZUL0001", LATITUDE, LONGITUDE, WebhookEventType.PARKED);
    }
}
