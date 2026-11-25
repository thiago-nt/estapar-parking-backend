package br.com.estapar.parking.application;

import br.com.estapar.parking.infrastructure.simulator.GarageSimulatorException;
import br.com.estapar.parking.infrastructure.simulator.GarageSimulatorClient;
import br.com.estapar.parking.infrastructure.simulator.GarageSimulatorProperties;
import br.com.estapar.parking.infrastructure.simulator.GarageSimulatorUnavailableException;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageConfigurationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class GarageInitializationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GarageInitializationService.class);

    private final GarageSimulatorProperties properties;
    private final GarageSimulatorClient simulatorClient;
    private final GarageConfigurationValidator validator;
    private final GarageSyncService syncService;
    private final GarageInitializationState state;

    public GarageInitializationService(
            GarageSimulatorProperties properties,
            GarageSimulatorClient simulatorClient,
            GarageConfigurationValidator validator,
            GarageSyncService syncService,
            GarageInitializationState state
    ) {
        this.properties = properties;
        this.simulatorClient = simulatorClient;
        this.validator = validator;
        this.syncService = syncService;
        this.state = state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!properties.isEnabled()) {
            LOGGER.info("Automatic garage initialization is disabled");
            return;
        }

        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                GarageConfigurationResponse configuration = simulatorClient.fetchConfiguration();
                validator.validate(configuration);
                GarageSyncResult result = syncService.synchronize(configuration);
                state.markReady();
                LOGGER.info(
                        "Garage initialization completed sectorsReceived={} spotsReceived={} sectorsInserted={} "
                                + "sectorsUpdated={} spotsInserted={} spotsUpdated={}",
                        result.sectorsReceived(),
                        result.spotsReceived(),
                        result.sectorsInserted(),
                        result.sectorsUpdated(),
                        result.spotsInserted(),
                        result.spotsUpdated()
                );
                return;
            } catch (GarageSimulatorUnavailableException exception) {
                if (attempt == maxAttempts) {
                    failInitialization(exception);
                    return;
                }
                LOGGER.warn(
                        "Garage simulator unavailable during initialization attempt {}/{}; retrying after {}",
                        attempt,
                        maxAttempts,
                        properties.getRetry().getBackoff()
                );
                if (!waitBeforeRetry()) {
                    return;
                }
            } catch (InvalidGarageConfigurationException | GarageSimulatorException | GarageSyncConflictException exception) {
                failInitialization(exception);
                return;
            } catch (RuntimeException exception) {
                failInitialization(exception);
                return;
            }
        }
    }

    private boolean waitBeforeRetry() {
        try {
            Thread.sleep(Math.max(0, properties.getRetry().getBackoff().toMillis()));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failInitialization(exception);
            return false;
        }
    }

    private void failInitialization(Exception exception) {
        String reason = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        state.markFailed(reason);
        LOGGER.error("Garage initialization failed; application is not ready to process events: {}", reason);
    }
}
