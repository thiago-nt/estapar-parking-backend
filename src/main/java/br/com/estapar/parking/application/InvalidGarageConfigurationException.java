package br.com.estapar.parking.application;

public class InvalidGarageConfigurationException extends RuntimeException {

    public InvalidGarageConfigurationException(String message) {
        super(message);
    }
}
