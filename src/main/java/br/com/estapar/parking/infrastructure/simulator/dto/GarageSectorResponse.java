package br.com.estapar.parking.infrastructure.simulator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GarageSectorResponse(
        @JsonProperty("sector") String sector,
        @JsonAlias("base_price")
        @JsonProperty("basePrice") BigDecimal basePrice,
        @JsonProperty("max_capacity") Integer maxCapacity
) {
}
