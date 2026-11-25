package br.com.estapar.parking.infrastructure.simulator;

public class GarageSimulatorUnavailableException extends GarageSimulatorException {

    public GarageSimulatorUnavailableException(String message) {
        super(message);
    }

    public GarageSimulatorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
