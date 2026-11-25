package br.com.estapar.parking.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExitEventRequest(
        @JsonProperty("license_plate") @NotBlank String licensePlate,
        @JsonProperty("exit_time") @NotNull Instant exitTime,
        @JsonProperty("event_type") @NotNull WebhookEventType eventType
) implements WebhookEventRequest {
}
