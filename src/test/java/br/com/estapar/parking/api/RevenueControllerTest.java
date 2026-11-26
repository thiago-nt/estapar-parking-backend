package br.com.estapar.parking.api;

import br.com.estapar.parking.api.dto.RevenueResponse;
import br.com.estapar.parking.api.error.GlobalExceptionHandler;
import br.com.estapar.parking.application.ParkingEventProcessingUnavailableException;
import br.com.estapar.parking.application.RevenueService;
import br.com.estapar.parking.application.SectorNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RevenueControllerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-25T20:00:00Z");

    private RevenueService revenueService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        revenueService = mock(RevenueService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RevenueController(revenueService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(revenueService.getRevenue(any(), any()))
                .thenReturn(new RevenueResponse(new BigDecimal("72.90"), "BRL", TIMESTAMP));
    }

    @Test
    void shouldAcceptPrimaryQueryParameterContract() throws Exception {
        mockMvc.perform(get("/revenue").queryParam("date", "2025-01-01").queryParam("sector", "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(72.90))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-25T20:00:00Z"));

        verify(revenueService).getRevenue(LocalDate.of(2025, 1, 1), "A");
    }

    @Test
    void shouldAllowDateToBeAbsent() throws Exception {
        mockMvc.perform(get("/revenue").queryParam("sector", "A"))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> date = ArgumentCaptor.forClass(LocalDate.class);
        verify(revenueService).getRevenue(date.capture(), eq("A"));
        assertEquals(null, date.getValue());
    }

    @Test
    void shouldAcceptCompatibleJsonBodyContract() throws Exception {
        mockMvc.perform(get("/revenue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"2025-01-01","sector":"A"}
                                """))
                .andExpect(status().isOk());

        verify(revenueService).getRevenue(LocalDate.of(2025, 1, 1), "A");
    }

    @Test
    void shouldAcceptEqualQueryAndBodyValues() throws Exception {
        mockMvc.perform(get("/revenue")
                        .queryParam("date", "2025-01-01")
                        .queryParam("sector", "a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"2025-01-01","sector":" A "}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnBadRequestForInvalidQueryDate() throws Exception {
        mockMvc.perform(get("/revenue").queryParam("date", "01-01-2025").queryParam("sector", "A"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(revenueService, never()).getRevenue(any(), any());
    }

    @Test
    void shouldReturnBadRequestForMalformedBodyDate() throws Exception {
        mockMvc.perform(get("/revenue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"invalid","sector":"A"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnBadRequestWhenSectorIsMissing() throws Exception {
        when(revenueService.getRevenue(null, null))
                .thenThrow(new br.com.estapar.parking.application.InvalidRevenueRequestException("Sector is required"));

        mockMvc.perform(get("/revenue"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldReturnBadRequestForConflictingDate() throws Exception {
        mockMvc.perform(get("/revenue")
                        .queryParam("date", "2025-01-01")
                        .queryParam("sector", "A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"2025-01-02","sector":"A"}
                                """))
                .andExpect(status().isBadRequest());

        verify(revenueService, never()).getRevenue(any(), any());
    }

    @Test
    void shouldReturnBadRequestForConflictingSector() throws Exception {
        mockMvc.perform(get("/revenue")
                        .queryParam("sector", "A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sector":"B"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFoundForUnknownSector() throws Exception {
        when(revenueService.getRevenue(null, "X")).thenThrow(new SectorNotFoundException("Sector was not found: X"));

        mockMvc.perform(get("/revenue").queryParam("sector", "X"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SECTOR_NOT_FOUND"));
    }

    @Test
    void shouldReturnServiceUnavailableWhileGarageIsNotReady() throws Exception {
        when(revenueService.getRevenue(null, "A"))
                .thenThrow(new ParkingEventProcessingUnavailableException("Garage is not ready"));

        mockMvc.perform(get("/revenue").queryParam("sector", "A"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROCESSING_UNAVAILABLE"));
    }
}
