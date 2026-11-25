package br.com.estapar.parking.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EntryEventRequest(
        @JsonProperty("license_plate") @NotBlank String licensePlate,
        @JsonProperty("entry_time") @NotNull Instant entryTime,
        @JsonProperty("event_type") @NotNull WebhookEventType eventType
) implements WebhookEventRequest {
}
