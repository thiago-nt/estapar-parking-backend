package br.com.estapar.parking.infrastructure.simulator;

import br.com.estapar.parking.infrastructure.simulator.dto.GarageConfigurationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GarageSimulatorClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(GarageSimulatorClient.class);

    private final RestClient restClient;

    public GarageSimulatorClient(RestClient garageSimulatorRestClient) {
        this.restClient = garageSimulatorRestClient;
    }

    public GarageConfigurationResponse fetchConfiguration() {
        LOGGER.info("Fetching garage configuration from simulator");

        try {
            GarageConfigurationResponse response = restClient.get()
                    .uri("/garage")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        HttpStatusCode status = httpResponse.getStatusCode();
                        if (status.is5xxServerError()) {
                            throw new GarageSimulatorUnavailableException(
                                    "Garage simulator returned transient HTTP status " + status.value()
                            );
                        }
                        throw new GarageSimulatorException(
                                "Garage simulator returned HTTP status " + status.value()
                        );
                    })
                    .body(GarageConfigurationResponse.class);

            if (response == null) {
                throw new InvalidGarageSimulatorResponseException("Garage simulator returned an empty response");
            }
            return response;
        } catch (GarageSimulatorException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new GarageSimulatorUnavailableException(
                    "Garage simulator is unavailable or exceeded the configured timeout",
                    exception
            );
        } catch (RestClientException exception) {
            throw new InvalidGarageSimulatorResponseException(
                    "Garage simulator returned a response that could not be decoded",
                    exception
            );
        }
    }
}
