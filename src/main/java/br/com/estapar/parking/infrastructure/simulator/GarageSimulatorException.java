package br.com.estapar.parking.infrastructure.simulator;

public class GarageSimulatorException extends RuntimeException {

    public GarageSimulatorException(String message) {
        super(message);
    }

    public GarageSimulatorException(String message, Throwable cause) {
        super(message, cause);
    }
}
