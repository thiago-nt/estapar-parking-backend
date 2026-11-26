package br.com.estapar.parking.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicPricingPolicyTest {

    private final DynamicPricingPolicy policy = new DynamicPricingPolicy();

    @ParameterizedTest
    @CsvSource({
            "0,   100, 0.90",
            "24,  100, 0.90",
            "25,  100, 1.00",
            "50,  100, 1.00",
            "51,  100, 1.10",
            "75,  100, 1.10",
            "76,  100, 1.25",
            "100, 100, 1.25"
    })
    void shouldCalculateMultiplierFromExactSectorOccupancy(
            long occupiedSpots,
            int maxCapacity,
            String expectedMultiplier
    ) {
        BigDecimal multiplier = policy.calculateMultiplier(occupiedSpots, maxCapacity);

        assertEquals(new BigDecimal(expectedMultiplier), multiplier);
    }

    @Test
    void shouldRejectZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> policy.calculateMultiplier(0, 0));
    }

    @Test
    void shouldRejectNegativeOccupancy() {
        assertThrows(IllegalArgumentException.class, () -> policy.calculateMultiplier(-1, 10));
    }

    @Test
    void shouldRejectOccupancyAboveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> policy.calculateMultiplier(11, 10));
    }
}
