package br.com.estapar.parking.api;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.api.dto.WebhookEventRequest;
import br.com.estapar.parking.application.WebhookEventDispatcher;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookEventDispatcher dispatcher;

    public WebhookController(WebhookEventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping(path = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(@Valid @RequestBody WebhookEventRequest event) {
        LOGGER.info("Received validated webhook event type={}", event.eventType());

        switch (event) {
            case EntryEventRequest entry -> dispatcher.dispatch(entry);
            case ParkedEventRequest parked -> dispatcher.dispatch(parked);
            case ExitEventRequest exit -> dispatcher.dispatch(exit);
        }

        LOGGER.info("Webhook event processed successfully type={}", event.eventType());
        return ResponseEntity.ok().build();
    }
}
