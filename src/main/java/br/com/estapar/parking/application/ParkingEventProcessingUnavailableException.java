package br.com.estapar.parking.application;

public class ParkingEventProcessingUnavailableException extends RuntimeException {

    public ParkingEventProcessingUnavailableException(String message) {
        super(message);
    }
}
