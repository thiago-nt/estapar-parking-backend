package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventType;
import br.com.estapar.parking.domain.model.GarageState;
import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.domain.model.ParkingSpot;
import br.com.estapar.parking.domain.model.ParkingSpotStatus;
import br.com.estapar.parking.domain.model.Sector;
import br.com.estapar.parking.domain.service.PricingService;
import br.com.estapar.parking.infrastructure.persistence.repository.GarageStateRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSpotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParkingExitServiceTest {

    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");
    private static final Instant PARKED_AT = Instant.parse("2025-01-01T12:05:00Z");
    private static final Instant EXIT_TIME = Instant.parse("2025-01-01T13:30:00Z");
    private static final BigDecimal BASE_PRICE = new BigDecimal("10.00");
    private static final BigDecimal MULTIPLIER = new BigDecimal("1.10");
    private static final BigDecimal AMOUNT = new BigDecimal("22.00");

    private GarageInitializationState initializationState;
    private GarageStateRepository garageStateRepository;
    private ParkingSessionRepository parkingSessionRepository;
    private ParkingSpotRepository parkingSpotRepository;
    private PricingService pricingService;
    private Sector sector;
    private ParkingSpot spot;
    private ParkingSession session;
    private ParkingExitService service;

    @BeforeEach
    void setUp() {
        initializationState = new GarageInitializationState();
        initializationState.markReady();
        garageStateRepository = mock(GarageStateRepository.class);
        parkingSessionRepository = mock(ParkingSessionRepository.class);
        parkingSpotRepository = mock(ParkingSpotRepository.class);
        pricingService = mock(PricingService.class);
        service = new ParkingExitService(
                initializationState,
                garageStateRepository,
                parkingSessionRepository,
                parkingSpotRepository,
                pricingService
        );

        sector = new Sector("A", BASE_PRICE, 10);
        spot = new ParkingSpot(1L, sector, BigDecimal.ZERO, BigDecimal.ZERO);
        session = new ParkingSession("ZUL0001", ENTRY_TIME);
        session.assignParkingSpot(spot, PARKED_AT, MULTIPLIER);
        spot.occupy();

        when(garageStateRepository.findControlStateForUpdate()).thenReturn(Optional.of(mock(GarageState.class)));
        when(parkingSessionRepository.existsByLicensePlateAndStatusAndExitTime(
                "ZUL0001", ParkingSessionStatus.EXITED, EXIT_TIME
        )).thenReturn(false);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.of(session));
        when(pricingService.calculate(ENTRY_TIME, EXIT_TIME, BASE_PRICE, MULTIPLIER)).thenReturn(AMOUNT);
    }

    @Test
    void shouldFinishValidParkedSession() {
        service.processExit(event(EXIT_TIME));

        assertEquals(ParkingSessionStatus.EXITED, session.getStatus());
        verify(parkingSessionRepository).saveAndFlush(session);
    }

    @Test
    void shouldChangeStatusToExited() {
        service.processExit(event(EXIT_TIME));

        assertEquals(ParkingSessionStatus.EXITED, session.getStatus());
    }

    @Test
    void shouldPersistExitTimeTruncatedToDatabasePrecision() {
        Instant received = Instant.parse("2025-01-01T13:30:00.123456789Z");
        Instant persisted = Instant.parse("2025-01-01T13:30:00.123456Z");
        when(parkingSessionRepository.existsByLicensePlateAndStatusAndExitTime(
                "ZUL0001", ParkingSessionStatus.EXITED, persisted
        )).thenReturn(false);
        when(pricingService.calculate(ENTRY_TIME, persisted, BASE_PRICE, MULTIPLIER)).thenReturn(AMOUNT);

        service.processExit(event(received));

        assertEquals(persisted, session.getExitTime());
    }

    @Test
    void shouldPersistCalculatedAmount() {
        service.processExit(event(EXIT_TIME));

        assertEquals(AMOUNT, session.getAmount());
    }

    @Test
    void shouldReleaseParkingSpot() {
        service.processExit(event(EXIT_TIME));

        assertEquals(ParkingSpotStatus.AVAILABLE, spot.getStatus());
        verify(parkingSpotRepository).saveAndFlush(spot);
    }

    @Test
    void shouldPreserveHistoricalSpotAndSectorAssociations() {
        service.processExit(event(EXIT_TIME));

        assertSame(spot, session.getParkingSpot());
        assertSame(sector, session.getSector());
    }

    @Test
    void shouldPersistZeroAmountForFreeStay() {
        BigDecimal freeAmount = new BigDecimal("0.00");
        when(pricingService.calculate(ENTRY_TIME, EXIT_TIME, BASE_PRICE, MULTIPLIER)).thenReturn(freeAmount);

        service.processExit(event(EXIT_TIME));

        assertEquals(freeAmount, session.getAmount());
    }

    @Test
    void shouldTreatDuplicateExitAsIdempotent() {
        when(parkingSessionRepository.existsByLicensePlateAndStatusAndExitTime(
                "ZUL0001", ParkingSessionStatus.EXITED, EXIT_TIME
        )).thenReturn(true);

        assertDoesNotThrow(() -> service.processExit(event(EXIT_TIME)));
        verify(parkingSessionRepository, never()).findByLicensePlateAndStatusIn(any(), anyCollection());
    }

    @Test
    void shouldNotRecalculateAmountForDuplicateExit() {
        when(parkingSessionRepository.existsByLicensePlateAndStatusAndExitTime(
                "ZUL0001", ParkingSessionStatus.EXITED, EXIT_TIME
        )).thenReturn(true);

        service.processExit(event(EXIT_TIME));

        verify(pricingService, never()).calculate(any(), any(), any(), any());
        verify(parkingSessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldNotChangeExitTimeForDuplicateExit() {
        session.finish(EXIT_TIME, AMOUNT);
        spot.release();
        when(parkingSessionRepository.existsByLicensePlateAndStatusAndExitTime(
                "ZUL0001", ParkingSessionStatus.EXITED, EXIT_TIME
        )).thenReturn(true);

        service.processExit(event(EXIT_TIME));

        assertEquals(EXIT_TIME, session.getExitTime());
        assertEquals(AMOUNT, session.getAmount());
    }

    @Test
    void shouldRejectExitBeforeEntry() {
        Instant invalidExit = ENTRY_TIME.minusMillis(1);

        assertThrows(InvalidParkingSessionStateException.class, () -> service.processExit(event(invalidExit)));
        assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
        assertEquals(ParkingSpotStatus.OCCUPIED, spot.getStatus());
    }

    @Test
    void shouldRejectExitWithoutApplicableSession() {
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.empty());

        assertThrows(ParkingSessionNotFoundException.class, () -> service.processExit(event(EXIT_TIME)));
    }

    @Test
    void shouldRejectExitForEnteredSession() {
        ParkingSession enteredSession = new ParkingSession("ZUL0001", ENTRY_TIME);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.of(enteredSession));

        assertThrows(InvalidParkingSessionStateException.class, () -> service.processExit(event(EXIT_TIME)));
    }

    @Test
    void shouldRejectParkedSessionWithoutBasePrice() {
        ParkingSession legacySession = legacySession(null, MULTIPLIER, spot);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.of(legacySession));

        assertThrows(IncompleteParkingSessionPricingException.class, () -> service.processExit(event(EXIT_TIME)));
        assertEquals(ParkingSpotStatus.OCCUPIED, spot.getStatus());
    }

    @Test
    void shouldRejectParkedSessionWithoutMultiplier() {
        ParkingSession legacySession = legacySession(BASE_PRICE, null, spot);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.of(legacySession));

        assertThrows(IncompleteParkingSessionPricingException.class, () -> service.processExit(event(EXIT_TIME)));
        assertEquals(ParkingSpotStatus.OCCUPIED, spot.getStatus());
    }

    @Test
    void shouldRejectInconsistentAvailableSpot() {
        spot.release();

        assertThrows(ParkingSpotStateConflictException.class, () -> service.processExit(event(EXIT_TIME)));
        assertEquals(ParkingSessionStatus.PARKED, session.getStatus());
    }

    @Test
    void shouldRejectExitWhileGarageIsNotReady() {
        ParkingExitService pendingService = new ParkingExitService(
                new GarageInitializationState(),
                garageStateRepository,
                parkingSessionRepository,
                parkingSpotRepository,
                pricingService
        );

        assertThrows(ParkingEventProcessingUnavailableException.class, () -> pendingService.processExit(event(EXIT_TIME)));
        verify(garageStateRepository, never()).findControlStateForUpdate();
    }

    @Test
    void shouldRecognizeOldDuplicateBeforeInspectingNewActiveSession() {
        ParkingSession newSession = new ParkingSession("ZUL0001", EXIT_TIME.plusSeconds(60));
        when(parkingSessionRepository.existsByLicensePlateAndStatusAndExitTime(
                "ZUL0001", ParkingSessionStatus.EXITED, EXIT_TIME
        )).thenReturn(true);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.of(newSession));

        service.processExit(event(EXIT_TIME));

        assertEquals(ParkingSessionStatus.ENTERED, newSession.getStatus());
        verify(parkingSessionRepository, never()).findByLicensePlateAndStatusIn(any(), anyCollection());
    }

    @Test
    void shouldUseFrozenPriceInsteadOfCurrentSectorPrice() {
        sector.updateConfiguration(new BigDecimal("99.99"), 10);

        service.processExit(event(EXIT_TIME));

        verify(pricingService).calculate(ENTRY_TIME, EXIT_TIME, BASE_PRICE, MULTIPLIER);
    }

    @Test
    void shouldPreserveEntryAndParkedTimestamps() {
        service.processExit(event(EXIT_TIME));

        assertEquals(ENTRY_TIME, session.getEntryTime());
        assertEquals(PARKED_AT, session.getParkedAt());
    }

    @Test
    void shouldRejectDifferentExitAfterSessionWasAlreadyFinished() {
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.empty());

        assertThrows(
                ParkingSessionNotFoundException.class,
                () -> service.processExit(event(EXIT_TIME.plusSeconds(1)))
        );
    }

    private ParkingSession legacySession(BigDecimal basePrice, BigDecimal multiplier, ParkingSpot parkingSpot) {
        ParkingSession legacy = mock(ParkingSession.class);
        when(legacy.getStatus()).thenReturn(ParkingSessionStatus.PARKED);
        when(legacy.getEntryTime()).thenReturn(ENTRY_TIME);
        when(legacy.getBasePriceAtParking()).thenReturn(basePrice);
        when(legacy.getAppliedMultiplier()).thenReturn(multiplier);
        when(legacy.getParkingSpot()).thenReturn(parkingSpot);
        when(legacy.getSector()).thenReturn(sector);
        return legacy;
    }

    private ExitEventRequest event(Instant exitTime) {
        return new ExitEventRequest("ZUL0001", exitTime, WebhookEventType.EXIT);
    }
}
