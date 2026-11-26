package br.com.estapar.parking.application;

public class IncompleteParkingSessionPricingException extends RuntimeException {

    public IncompleteParkingSessionPricingException(String message) {
        super(message);
    }
}
