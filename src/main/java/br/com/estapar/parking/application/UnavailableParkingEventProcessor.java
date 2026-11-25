package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import org.springframework.stereotype.Service;

@Service
public class UnavailableParkingEventProcessor implements ParkingEventProcessor {

    private static final String UNAVAILABLE_MESSAGE =
            "Parking event processing will be available in the next implementation stage";

    @Override
    public void processEntry(EntryEventRequest event) {
        throw unavailable();
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
