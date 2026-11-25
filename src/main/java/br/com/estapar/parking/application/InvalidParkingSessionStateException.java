package br.com.estapar.parking.application;

public class InvalidParkingSessionStateException extends RuntimeException {

    public InvalidParkingSessionStateException(String message) {
        super(message);
    }
}
