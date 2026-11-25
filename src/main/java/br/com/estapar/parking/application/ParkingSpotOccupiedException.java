package br.com.estapar.parking.application;

public class ParkingSpotOccupiedException extends RuntimeException {

    public ParkingSpotOccupiedException(String message) {
        super(message);
    }
}
