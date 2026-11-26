package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import org.springframework.stereotype.Service;

@Service
public class DefaultParkingEventProcessor implements ParkingEventProcessor {

    private final ParkingEntryService parkingEntryService;
    private final ParkingSpotAssignmentService parkingSpotAssignmentService;
    private final ParkingExitService parkingExitService;

    public DefaultParkingEventProcessor(
            ParkingEntryService parkingEntryService,
            ParkingSpotAssignmentService parkingSpotAssignmentService,
            ParkingExitService parkingExitService
    ) {
        this.parkingEntryService = parkingEntryService;
        this.parkingSpotAssignmentService = parkingSpotAssignmentService;
        this.parkingExitService = parkingExitService;
    }

    @Override
    public void processEntry(EntryEventRequest event) {
        parkingEntryService.processEntry(event);
    }

    @Override
    public void processParked(ParkedEventRequest event) {
        parkingSpotAssignmentService.processParked(event);
    }

    @Override
    public void processExit(ExitEventRequest event) {
        parkingExitService.processExit(event);
    }
}
