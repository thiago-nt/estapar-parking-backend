package br.com.estapar.parking.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class GarageInitializationState {

    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private volatile String failureReason;

    public Status getStatus() {
        return status.get();
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void markReady() {
        failureReason = null;
        status.set(Status.READY);
    }

    public void markFailed(String failureReason) {
        this.failureReason = failureReason;
        status.set(Status.FAILED);
    }

    public enum Status {
        PENDING,
        READY,
        FAILED
    }
}
