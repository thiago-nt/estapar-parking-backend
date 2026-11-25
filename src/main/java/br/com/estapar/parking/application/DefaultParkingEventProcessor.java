package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import org.springframework.stereotype.Service;

@Service
public class DefaultParkingEventProcessor implements ParkingEventProcessor {

    private static final String UNAVAILABLE_MESSAGE =
            "Parking event processing is not available for this event type yet";

    private final ParkingEntryService parkingEntryService;

    public DefaultParkingEventProcessor(ParkingEntryService parkingEntryService) {
        this.parkingEntryService = parkingEntryService;
    }

    @Override
    public void processEntry(EntryEventRequest event) {
        parkingEntryService.processEntry(event);
    }

    @Override
    public void processParked(ParkedEventRequest event) {
        throw unavailable();
    }

    @Override
    public void processExit(ExitEventRequest event) {
        throw unavailable();
    }

    private ParkingEventProcessingUnavailableException unavailable() {
        return new ParkingEventProcessingUnavailableException(UNAVAILABLE_MESSAGE);
    }
}
