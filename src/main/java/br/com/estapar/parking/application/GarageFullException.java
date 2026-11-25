package br.com.estapar.parking.application;

public class GarageFullException extends RuntimeException {

    public GarageFullException(String message) {
        super(message);
    }
}
