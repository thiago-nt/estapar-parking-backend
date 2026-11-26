package br.com.estapar.parking.api;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.application.ActiveParkingSessionConflictException;
import br.com.estapar.parking.application.GarageInitializationState;
import br.com.estapar.parking.application.GarageFullException;
import br.com.estapar.parking.application.IncompleteParkingSessionPricingException;
import br.com.estapar.parking.application.ParkingEventProcessor;
import br.com.estapar.parking.application.ParkingSpotNotFoundException;
import br.com.estapar.parking.application.ParkingSpotOccupiedException;
import br.com.estapar.parking.application.UnavailableParkingEventProcessor;
import br.com.estapar.parking.application.WebhookEventDispatcher;
import br.com.estapar.parking.api.error.GlobalExceptionHandler;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookControllerTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final SpringValidatorAdapter VALIDATOR =
            new SpringValidatorAdapter(VALIDATOR_FACTORY.getValidator());

    private ParkingEventProcessor processor;
    private GarageInitializationState initializationState;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        processor = mock(ParkingEventProcessor.class);
        initializationState = new GarageInitializationState();
        initializationState.markReady();
        mockMvc = mockMvc(processor, initializationState);
    }

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldRouteValidEntryAndConvertEntryTime() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "license_plate": "ZUL0001",
                                  "entry_time": "2025-01-01T12:00:00.000Z",
                                  "event_type": "ENTRY"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        ArgumentCaptor<EntryEventRequest> captor = ArgumentCaptor.forClass(EntryEventRequest.class);
        verify(processor).processEntry(captor.capture());
        assertEquals("ZUL0001", captor.getValue().licensePlate());
        assertEquals(Instant.parse("2025-01-01T12:00:00Z"), captor.getValue().entryTime());
    }

    @Test
    void shouldRejectEntryWithoutLicensePlate() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "entry_time": "2025-01-01T12:00:00Z",
                                  "event_type": "ENTRY"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldRouteValidParkedAndPreserveCoordinatePrecision() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "license_plate": "ZUL0001",
                                  "lat": -23.561684,
                                  "lng": -46.655981,
                                  "event_type": "PARKED"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ParkedEventRequest> captor = ArgumentCaptor.forClass(ParkedEventRequest.class);
        verify(processor).processParked(captor.capture());
        assertEquals(new BigDecimal("-23.561684"), captor.getValue().latitude());
        assertEquals(new BigDecimal("-46.655981"), captor.getValue().longitude());
    }

    @Test
    void shouldRejectParkedWithoutCoordinate() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "license_plate": "ZUL0001",
                                  "lat": -23.561684,
                                  "event_type": "PARKED"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectParkedWithLatitudeOutsideValidRange() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "license_plate": "ZUL0001",
                                  "lat": 90.0001,
                                  "lng": -46.655981,
                                  "event_type": "PARKED"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRouteValidExitAndConvertExitTime() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "license_plate": "ZUL0001",
                                  "exit_time": "2025-01-01T13:30:00.000Z",
                                  "event_type": "EXIT"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ExitEventRequest> captor = ArgumentCaptor.forClass(ExitEventRequest.class);
        verify(processor).processExit(captor.capture());
        assertEquals(Instant.parse("2025-01-01T13:30:00Z"), captor.getValue().exitTime());
    }

    @Test
    void shouldRejectExitWithInvalidTimestamp() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "license_plate": "ZUL0001",
                                  "exit_time": "not-an-instant",
                                  "event_type": "EXIT"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectUnknownEventType() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "license_plate": "ZUL0001",
                                  "event_type": "UNKNOWN"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("entry"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void shouldReturnServiceUnavailableForProvisionalProcessor() throws Exception {
        MockMvc unavailableMockMvc = mockMvc(new UnavailableParkingEventProcessor(), initializationState);

        unavailableMockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEntry()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROCESSING_UNAVAILABLE"));
    }

    @Test
    void shouldReturnServiceUnavailableWhileGarageIsNotReady() throws Exception {
        MockMvc pendingMockMvc = mockMvc(processor, new GarageInitializationState());

        pendingMockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEntry()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldReturnInternalServerErrorForUnexpectedFailure() throws Exception {
        doThrow(new IllegalStateException("unexpected failure"))
                .when(processor)
                .processEntry(any(EntryEventRequest.class));

        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEntry()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "An unexpected error occurred while processing the event"
                ));
    }

    @Test
    void shouldReturnConflictWhenGarageIsFull() throws Exception {
        doThrow(new GarageFullException("Garage has no available entry capacity"))
                .when(processor)
                .processEntry(any(EntryEventRequest.class));

        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEntry()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_CONFLICT"));
    }

    @Test
    void shouldReturnConflictForDifferentEntryTimeOnActiveSession() throws Exception {
        doThrow(new ActiveParkingSessionConflictException("Active session conflict"))
                .when(processor)
                .processEntry(any(EntryEventRequest.class));

        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEntry()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_CONFLICT"));
    }

    @Test
    void shouldReturnNotFoundWhenParkingSpotCoordinatesDoNotExist() throws Exception {
        doThrow(new ParkingSpotNotFoundException("Parking spot was not found"))
                .when(processor)
                .processParked(any(ParkedEventRequest.class));

        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validParked()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARKING_SPOT_NOT_FOUND"));
    }

    @Test
    void shouldReturnConflictWhenParkingSpotIsOccupied() throws Exception {
        doThrow(new ParkingSpotOccupiedException("Parking spot is already occupied"))
                .when(processor)
                .processParked(any(ParkedEventRequest.class));

        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validParked()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_CONFLICT"));
    }

    @Test
    void shouldReturnConflictWhenExitPricingIsIncomplete() throws Exception {
        doThrow(new IncompleteParkingSessionPricingException("Frozen pricing is incomplete"))
                .when(processor)
                .processExit(any(ExitEventRequest.class));

        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validExit()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_CONFLICT"));
    }

    private MockMvc mockMvc(ParkingEventProcessor eventProcessor, GarageInitializationState state) {
        WebhookEventDispatcher dispatcher = new WebhookEventDispatcher(eventProcessor, state);
        WebhookController controller = new WebhookController(dispatcher);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(VALIDATOR)
                .build();
    }

    private String validEntry() {
        return """
                {
                  "license_plate": "ZUL0001",
                  "entry_time": "2025-01-01T12:00:00Z",
                  "event_type": "ENTRY"
                }
                """;
    }

    private String validParked() {
        return """
                {
                  "license_plate": "ZUL0001",
                  "lat": -23.561684,
                  "lng": -46.655981,
                  "event_type": "PARKED"
                }
                """;
    }

    private String validExit() {
        return """
                {
                  "license_plate": "ZUL0001",
                  "exit_time": "2025-01-01T13:30:00Z",
                  "event_type": "EXIT"
                }
                """;
    }
}
