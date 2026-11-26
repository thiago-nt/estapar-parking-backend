package br.com.estapar.parking.api.dto;

import java.time.LocalDate;

public record RevenueRequest(LocalDate date, String sector) {
}
