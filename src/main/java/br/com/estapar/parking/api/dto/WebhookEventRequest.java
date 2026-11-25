package br.com.estapar.parking.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "event_type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = EntryEventRequest.class, name = "ENTRY"),
        @JsonSubTypes.Type(value = ParkedEventRequest.class, name = "PARKED"),
        @JsonSubTypes.Type(value = ExitEventRequest.class, name = "EXIT")
})
public sealed interface WebhookEventRequest permits EntryEventRequest, ParkedEventRequest, ExitEventRequest {

    String licensePlate();

    WebhookEventType eventType();
}
