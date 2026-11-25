package br.com.estapar.parking.application;

public class ParkingSpotNotFoundException extends RuntimeException {

    public ParkingSpotNotFoundException(String message) {
        super(message);
    }
}
