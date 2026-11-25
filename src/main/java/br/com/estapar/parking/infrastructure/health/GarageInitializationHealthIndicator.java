package br.com.estapar.parking.infrastructure.health;

import br.com.estapar.parking.application.GarageInitializationState;
import br.com.estapar.parking.infrastructure.simulator.GarageSimulatorProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

@Component
public class GarageInitializationHealthIndicator implements HealthIndicator {

    private final GarageSimulatorProperties properties;
    private final GarageInitializationState state;

    public GarageInitializationHealthIndicator(
            GarageSimulatorProperties properties,
            GarageInitializationState state
    ) {
        this.properties = properties;
        this.state = state;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up().withDetail("initialization", "disabled").build();
        }
        if (state.getStatus() == GarageInitializationState.Status.READY) {
            return Health.up().withDetail("initialization", "ready").build();
        }
        return Health.status(Status.OUT_OF_SERVICE)
                .withDetail("initialization", state.getStatus().name().toLowerCase())
                .withDetail("reason", state.getFailureReason() == null ? "initialization pending" : state.getFailureReason())
                .build();
    }
}
