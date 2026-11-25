package br.com.estapar.parking.infrastructure.simulator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageConfigurationResponse(
        @JsonProperty("garage") List<GarageSectorResponse> garage,
        @JsonProperty("spots") List<GarageSpotResponse> spots
) {
}
