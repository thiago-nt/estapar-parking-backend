package br.com.estapar.parking.application;

public class SectorNotFoundException extends RuntimeException {

    public SectorNotFoundException(String message) {
        super(message);
    }
}
