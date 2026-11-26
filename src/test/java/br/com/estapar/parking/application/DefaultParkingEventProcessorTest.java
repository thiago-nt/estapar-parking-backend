package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DefaultParkingEventProcessorTest {

    private final ParkingEntryService entryService = mock(ParkingEntryService.class);
    private final ParkingSpotAssignmentService spotAssignmentService = mock(ParkingSpotAssignmentService.class);
    private final ParkingExitService exitService = mock(ParkingExitService.class);
    private final DefaultParkingEventProcessor processor =
            new DefaultParkingEventProcessor(entryService, spotAssignmentService, exitService);

    @Test
    void shouldDelegateEntryToRealEntryService() {
        EntryEventRequest event = new EntryEventRequest(
                "ZUL0001",
                Instant.parse("2025-01-01T12:00:00Z"),
                WebhookEventType.ENTRY
        );

        processor.processEntry(event);

        verify(entryService).processEntry(event);
    }

    @Test
    void shouldDelegateParkedToRealAssignmentService() {
        ParkedEventRequest event = new ParkedEventRequest(
                "ZUL0001",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                WebhookEventType.PARKED
        );

        processor.processParked(event);

        verify(spotAssignmentService).processParked(event);
    }

    @Test
    void shouldDelegateExitToRealExitService() {
        ExitEventRequest event = new ExitEventRequest(
                "ZUL0001",
                Instant.parse("2025-01-01T13:00:00Z"),
                WebhookEventType.EXIT
        );

        processor.processExit(event);

        verify(exitService).processExit(event);
    }
}
