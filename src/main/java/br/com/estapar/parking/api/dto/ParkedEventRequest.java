package br.com.estapar.parking.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ParkedEventRequest(
        @JsonProperty("license_plate") @NotBlank @Size(max = 20) String licensePlate,
        @JsonProperty("lat")
        @NotNull
        @DecimalMin("-90")
        @DecimalMax("90")
        BigDecimal latitude,
        @JsonProperty("lng")
        @NotNull
        @DecimalMin("-180")
        @DecimalMax("180")
        BigDecimal longitude,
        @JsonProperty("event_type") @NotNull WebhookEventType eventType
) implements WebhookEventRequest {
}
