package br.com.estapar.parking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ParkingApplicationTest {

    @Test
    void applicationEntryPointHasSpringBootConfiguration() {
        assertNotNull(ParkingApplication.class.getAnnotation(SpringBootApplication.class));
    }
}
