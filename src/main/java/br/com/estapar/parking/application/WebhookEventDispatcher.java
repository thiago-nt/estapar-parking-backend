package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookEventDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookEventDispatcher.class);

    private final ParkingEventProcessor processor;
    private final GarageInitializationState initializationState;

    public WebhookEventDispatcher(
            ParkingEventProcessor processor,
            GarageInitializationState initializationState
    ) {
        this.processor = processor;
        this.initializationState = initializationState;
    }

    public void dispatch(EntryEventRequest event) {
        ensureReady();
        LOGGER.info("Dispatching parking event type={}", event.eventType());
        processor.processEntry(event);
    }

    public void dispatch(ParkedEventRequest event) {
        ensureReady();
        LOGGER.info("Dispatching parking event type={}", event.eventType());
        processor.processParked(event);
    }

    public void dispatch(ExitEventRequest event) {
        ensureReady();
        LOGGER.info("Dispatching parking event type={}", event.eventType());
        processor.processExit(event);
    }

    private void ensureReady() {
        if (initializationState.getStatus() != GarageInitializationState.Status.READY) {
            throw new ParkingEventProcessingUnavailableException(
                    "Garage configuration is not ready for parking event processing"
            );
        }
    }
}
