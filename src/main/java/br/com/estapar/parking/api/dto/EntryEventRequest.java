package br.com.estapar.parking.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EntryEventRequest(
        @JsonProperty("license_plate") @NotBlank @Size(max = 20) String licensePlate,
        @JsonProperty("entry_time") @JsonDeserialize(using = WebhookInstantDeserializer.class) @NotNull Instant entryTime,
        @JsonProperty("event_type") @NotNull WebhookEventType eventType
) implements WebhookEventRequest {
}
