package br.com.estapar.parking.application;

public class ActiveParkingSessionConflictException extends RuntimeException {

    public ActiveParkingSessionConflictException(String message) {
        super(message);
    }
}
