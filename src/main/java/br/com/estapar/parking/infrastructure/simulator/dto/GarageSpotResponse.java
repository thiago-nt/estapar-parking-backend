package br.com.estapar.parking.infrastructure.simulator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageSpotResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("sector") String sector,
        @JsonProperty("lat") BigDecimal latitude,
        @JsonProperty("lng") BigDecimal longitude
) {
}
