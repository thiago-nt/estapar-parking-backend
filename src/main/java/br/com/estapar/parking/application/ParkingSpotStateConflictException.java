package br.com.estapar.parking.application;

public class ParkingSpotStateConflictException extends RuntimeException {

    public ParkingSpotStateConflictException(String message) {
        super(message);
    }
}
