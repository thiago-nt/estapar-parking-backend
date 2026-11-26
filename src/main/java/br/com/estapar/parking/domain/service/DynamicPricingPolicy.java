package br.com.estapar.parking.domain.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DynamicPricingPolicy {

    private static final BigDecimal DISCOUNT_MULTIPLIER = new BigDecimal("0.90");
    private static final BigDecimal REGULAR_MULTIPLIER = new BigDecimal("1.00");
    private static final BigDecimal MODERATE_DEMAND_MULTIPLIER = new BigDecimal("1.10");
    private static final BigDecimal HIGH_DEMAND_MULTIPLIER = new BigDecimal("1.25");

    public BigDecimal calculateMultiplier(long occupiedSpots, int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be greater than zero");
        }
        if (occupiedSpots < 0) {
            throw new IllegalArgumentException("occupiedSpots must not be negative");
        }
        if (occupiedSpots > maxCapacity) {
            throw new IllegalArgumentException("occupiedSpots must not exceed maxCapacity");
        }

        if (occupiedSpots * 4 < maxCapacity) {
            return DISCOUNT_MULTIPLIER;
        }
        if (occupiedSpots * 2 <= maxCapacity) {
            return REGULAR_MULTIPLIER;
        }
        if (occupiedSpots * 4 <= (long) maxCapacity * 3) {
            return MODERATE_DEMAND_MULTIPLIER;
        }
        return HIGH_DEMAND_MULTIPLIER;
    }
}
