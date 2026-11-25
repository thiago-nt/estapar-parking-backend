package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;

public interface ParkingEventProcessor {

    void processEntry(EntryEventRequest event);

    void processParked(ParkedEventRequest event);

    void processExit(ExitEventRequest event);
}
