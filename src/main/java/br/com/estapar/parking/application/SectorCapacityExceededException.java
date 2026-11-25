package br.com.estapar.parking.application;

public class SectorCapacityExceededException extends RuntimeException {

    public SectorCapacityExceededException(String message) {
        super(message);
    }
}
