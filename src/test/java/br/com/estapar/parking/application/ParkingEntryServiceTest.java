package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventType;
import br.com.estapar.parking.domain.model.GarageState;
import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.infrastructure.persistence.repository.GarageStateRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.SectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParkingEntryServiceTest {

    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");

    private GarageInitializationState initializationState;
    private GarageStateRepository garageStateRepository;
    private ParkingSessionRepository parkingSessionRepository;
    private SectorRepository sectorRepository;
    private ParkingEntryService service;

    @BeforeEach
    void setUp() {
        initializationState = new GarageInitializationState();
        initializationState.markReady();
        garageStateRepository = mock(GarageStateRepository.class);
        parkingSessionRepository = mock(ParkingSessionRepository.class);
        sectorRepository = mock(SectorRepository.class);
        service = new ParkingEntryService(
                initializationState,
                garageStateRepository,
                parkingSessionRepository,
                sectorRepository
        );

        when(garageStateRepository.findControlStateForUpdate()).thenReturn(Optional.of(mock(GarageState.class)));
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(any(), anyCollection()))
                .thenReturn(Optional.empty());
        when(sectorRepository.sumMaxCapacity()).thenReturn(30L);
        when(parkingSessionRepository.countByStatusIn(anyCollection())).thenReturn(0L);
    }

    @Test
    void shouldCreateEnteredSessionForValidEntry() {
        service.processEntry(event("ZUL0001", ENTRY_TIME));

        ArgumentCaptor<ParkingSession> captor = ArgumentCaptor.forClass(ParkingSession.class);
        verify(parkingSessionRepository).saveAndFlush(captor.capture());
        ParkingSession session = captor.getValue();
        assertEquals("ZUL0001", session.getLicensePlate());
        assertEquals(ENTRY_TIME, session.getEntryTime());
        assertEquals(ParkingSessionStatus.ENTERED, session.getStatus());
    }

    @Test
    void shouldTreatSameActiveEntryAsIdempotent() {
        ParkingSession existing = new ParkingSession("ZUL0001", ENTRY_TIME);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(eq("ZUL0001"), anyCollection()))
                .thenReturn(Optional.of(existing));

        service.processEntry(event("ZUL0001", ENTRY_TIME));

        verify(parkingSessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldPreserveOriginalTimeForIdempotentDelivery() {
        ParkingSession existing = new ParkingSession("ZUL0001", ENTRY_TIME);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(eq("ZUL0001"), anyCollection()))
                .thenReturn(Optional.of(existing));

        service.processEntry(event("ZUL0001", ENTRY_TIME));

        assertEquals(ENTRY_TIME, existing.getEntryTime());
    }

    @Test
    void shouldRejectSamePlateWithDifferentEntryTime() {
        ParkingSession existing = new ParkingSession("ZUL0001", ENTRY_TIME);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(eq("ZUL0001"), anyCollection()))
                .thenReturn(Optional.of(existing));

        assertThrows(
                ActiveParkingSessionConflictException.class,
                () -> service.processEntry(event("ZUL0001", ENTRY_TIME.plusSeconds(1)))
        );
        verify(parkingSessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectEntryWhenGarageIsFull() {
        when(sectorRepository.sumMaxCapacity()).thenReturn(2L);
        when(parkingSessionRepository.countByStatusIn(anyCollection())).thenReturn(2L);

        assertThrows(GarageFullException.class, () -> service.processEntry(event("ZUL0001", ENTRY_TIME)));
        verify(parkingSessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldAcceptIdempotentRetryEvenWhenGarageIsFull() {
        ParkingSession existing = new ParkingSession("ZUL0001", ENTRY_TIME);
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(eq("ZUL0001"), anyCollection()))
                .thenReturn(Optional.of(existing));
        when(sectorRepository.sumMaxCapacity()).thenReturn(1L);
        when(parkingSessionRepository.countByStatusIn(anyCollection())).thenReturn(1L);

        service.processEntry(event("ZUL0001", ENTRY_TIME));

        verify(sectorRepository, never()).sumMaxCapacity();
        verify(parkingSessionRepository, never()).countByStatusIn(anyCollection());
    }

    @Test
    void shouldAllowNewEntryWhenThereIsNoActiveSessionForPlate() {
        when(parkingSessionRepository.findByLicensePlateAndStatusIn(eq("ZUL0001"), anyCollection()))
                .thenReturn(Optional.empty());

        service.processEntry(event("ZUL0001", ENTRY_TIME));

        verify(parkingSessionRepository).saveAndFlush(any(ParkingSession.class));
    }

    @Test
    void shouldNotAssociatePhysicalSpotOrSectorDuringEntry() {
        service.processEntry(event("ZUL0001", ENTRY_TIME));

        ArgumentCaptor<ParkingSession> captor = ArgumentCaptor.forClass(ParkingSession.class);
        verify(parkingSessionRepository).saveAndFlush(captor.capture());
        assertNull(captor.getValue().getParkingSpot());
        assertNull(captor.getValue().getSector());
        assertNull(captor.getValue().getParkedAt());
        assertNull(captor.getValue().getExitTime());
        assertNull(captor.getValue().getAmount());
    }

    @Test
    void shouldUseCapacityAggregatedAcrossAllSectors() {
        when(sectorRepository.sumMaxCapacity()).thenReturn(30L);
        when(parkingSessionRepository.countByStatusIn(anyCollection())).thenReturn(29L);

        service.processEntry(event("ZUL0001", ENTRY_TIME));

        verify(sectorRepository).sumMaxCapacity();
        verify(parkingSessionRepository).saveAndFlush(any(ParkingSession.class));
    }

    @Test
    void shouldCountEnteredAndParkedSessionsAsActive() {
        service.processEntry(event("ZUL0001", ENTRY_TIME));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ParkingSessionStatus>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(parkingSessionRepository).countByStatusIn(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals(true, captor.getValue().contains(ParkingSessionStatus.ENTERED));
        assertEquals(true, captor.getValue().contains(ParkingSessionStatus.PARKED));
    }

    @Test
    void shouldRejectEntryWhenGarageInitializationIsNotReady() {
        ParkingEntryService pendingService = new ParkingEntryService(
                new GarageInitializationState(),
                garageStateRepository,
                parkingSessionRepository,
                sectorRepository
        );

        assertThrows(
                ParkingEventProcessingUnavailableException.class,
                () -> pendingService.processEntry(event("ZUL0001", ENTRY_TIME))
        );
        verify(garageStateRepository, never()).findControlStateForUpdate();
    }

    private EntryEventRequest event(String licensePlate, Instant entryTime) {
        return new EntryEventRequest(licensePlate, entryTime, WebhookEventType.ENTRY);
    }
}
