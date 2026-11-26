package br.com.estapar.parking.api.error;

import br.com.estapar.parking.application.ActiveParkingSessionConflictException;
import br.com.estapar.parking.application.GarageFullException;
import br.com.estapar.parking.application.IncompleteParkingSessionPricingException;
import br.com.estapar.parking.application.InvalidRevenueRequestException;
import br.com.estapar.parking.application.InvalidParkingSessionStateException;
import br.com.estapar.parking.application.ParkingEventProcessingUnavailableException;
import br.com.estapar.parking.application.ParkingSessionNotFoundException;
import br.com.estapar.parking.application.ParkingSpotNotFoundException;
import br.com.estapar.parking.application.ParkingSpotOccupiedException;
import br.com.estapar.parking.application.ParkingSpotStateConflictException;
import br.com.estapar.parking.application.SectorCapacityExceededException;
import br.com.estapar.parking.application.SectorNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            InvalidRevenueRequestException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        LOGGER.warn("HTTP request validation failed exceptionType={}", exception.getClass().getSimpleName());
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request payload is invalid");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        LOGGER.warn("Webhook request used an unsupported content type");
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json"
        );
    }

    @ExceptionHandler(ParkingEventProcessingUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnavailable(ParkingEventProcessingUnavailableException exception) {
        LOGGER.warn("Webhook event processing unavailable reason={}", exception.getMessage());
        return response(HttpStatus.SERVICE_UNAVAILABLE, "PROCESSING_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler({
            GarageFullException.class,
            ActiveParkingSessionConflictException.class,
            ParkingSessionNotFoundException.class,
            ParkingSpotOccupiedException.class,
            ParkingSpotStateConflictException.class,
            InvalidParkingSessionStateException.class,
            IncompleteParkingSessionPricingException.class,
            SectorCapacityExceededException.class
    })
    public ResponseEntity<ApiErrorResponse> handleConflict(RuntimeException exception) {
        LOGGER.warn("Webhook event processing conflict reason={}", exception.getMessage());
        return response(HttpStatus.CONFLICT, "PARKING_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(ParkingSpotNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleParkingSpotNotFound(ParkingSpotNotFoundException exception) {
        LOGGER.warn("Parking spot lookup failed reason={}", exception.getMessage());
        return response(HttpStatus.NOT_FOUND, "PARKING_SPOT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(SectorNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleSectorNotFound(SectorNotFoundException exception) {
        LOGGER.warn("Revenue sector lookup failed reason={}", exception.getMessage());
        return response(HttpStatus.NOT_FOUND, "SECTOR_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        LOGGER.error("Unexpected webhook processing failure", exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred while processing the event"
        );
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
