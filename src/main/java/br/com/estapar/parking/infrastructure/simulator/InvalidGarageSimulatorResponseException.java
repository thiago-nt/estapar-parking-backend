package br.com.estapar.parking.infrastructure.simulator;

public class InvalidGarageSimulatorResponseException extends GarageSimulatorException {

    public InvalidGarageSimulatorResponseException(String message) {
        super(message);
    }

    public InvalidGarageSimulatorResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
