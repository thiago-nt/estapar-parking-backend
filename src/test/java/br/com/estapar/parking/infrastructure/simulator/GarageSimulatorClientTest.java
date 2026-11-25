package br.com.estapar.parking.infrastructure.simulator;

import br.com.estapar.parking.infrastructure.simulator.dto.GarageConfigurationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GarageSimulatorClientTest {

    @Test
    void shouldDecodeValidResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GarageSimulatorClient client = new GarageSimulatorClient(builder.build());

        server.expect(requestTo("http://simulator.test/garage"))
                .andRespond(withSuccess("""
                        {
                          "garage": [
                            {"sector": "A", "basePrice": 10.50, "max_capacity": 100}
                          ],
                          "spots": [
                            {"id": 1, "sector": "A", "lat": -23.561684, "lng": -46.655981}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        GarageConfigurationResponse response = client.fetchConfiguration();

        assertEquals(1, response.garage().size());
        assertEquals(1, response.spots().size());
        assertEquals("10.50", response.garage().getFirst().basePrice().toPlainString());
        server.verify();
    }

    @Test
    void shouldDecodeBasePriceFieldReturnedByRealSimulator() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GarageSimulatorClient client = new GarageSimulatorClient(builder.build());

        server.expect(requestTo("http://simulator.test/garage"))
                .andRespond(withSuccess("""
                        {
                          "garage": [
                            {"sector": "A", "base_price": 40.50, "max_capacity": 10}
                          ],
                          "spots": []
                        }
                        """, MediaType.APPLICATION_JSON));

        GarageConfigurationResponse response = client.fetchConfiguration();

        assertEquals("40.50", response.garage().getFirst().basePrice().toPlainString());
        server.verify();
    }

    @Test
    void shouldRejectServerErrorAsTransientFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GarageSimulatorClient client = new GarageSimulatorClient(builder.build());

        server.expect(requestTo("http://simulator.test/garage"))
                .andRespond(withServerError());

        assertThrows(GarageSimulatorUnavailableException.class, client::fetchConfiguration);
        server.verify();
    }

    @Test
    void shouldRejectInvalidJson() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GarageSimulatorClient client = new GarageSimulatorClient(builder.build());

        server.expect(requestTo("http://simulator.test/garage"))
                .andRespond(withSuccess("{invalid-json", MediaType.APPLICATION_JSON));

        assertThrows(InvalidGarageSimulatorResponseException.class, client::fetchConfiguration);
        server.verify();
    }

    @Test
    void shouldReportUnavailableSimulator() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(100))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(100));
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .requestFactory(requestFactory)
                .build();
        GarageSimulatorClient client = new GarageSimulatorClient(restClient);

        assertThrows(GarageSimulatorUnavailableException.class, client::fetchConfiguration);
    }
}
