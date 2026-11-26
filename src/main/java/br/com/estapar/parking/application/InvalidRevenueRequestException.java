package br.com.estapar.parking.application;

public class InvalidRevenueRequestException extends RuntimeException {

    public InvalidRevenueRequestException(String message) {
        super(message);
    }
}
