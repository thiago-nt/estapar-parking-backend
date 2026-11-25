package br.com.estapar.parking.application;

public class ParkingSessionNotFoundException extends RuntimeException {

    public ParkingSessionNotFoundException(String message) {
        super(message);
    }
}
