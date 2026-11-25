package br.com.estapar.parking.application;

public class GarageSyncConflictException extends RuntimeException {

    public GarageSyncConflictException(String message) {
        super(message);
    }
}
