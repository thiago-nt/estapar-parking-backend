package br.com.estapar.parking.domain.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class PricingService {

    private static final Duration FREE_TOLERANCE = Duration.ofMinutes(30);
    private static final long SECONDS_PER_HOUR = Duration.ofHours(1).toSeconds();

    public BigDecimal calculate(
            Instant entryTime,
            Instant exitTime,
            BigDecimal basePrice,
            BigDecimal multiplier
    ) {
        Objects.requireNonNull(entryTime, "entryTime must not be null");
        Objects.requireNonNull(exitTime, "exitTime must not be null");
        Objects.requireNonNull(basePrice, "basePrice must not be null");
        Objects.requireNonNull(multiplier, "multiplier must not be null");

        if (exitTime.isBefore(entryTime)) {
            throw new IllegalArgumentException("exitTime must not be before entryTime");
        }
        if (basePrice.signum() < 0) {
            throw new IllegalArgumentException("basePrice must not be negative");
        }
        if (multiplier.signum() <= 0) {
            throw new IllegalArgumentException("multiplier must be greater than zero");
        }

        Duration duration = Duration.between(entryTime, exitTime);
        if (duration.compareTo(FREE_TOLERANCE) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        long billableHours = duration.getSeconds() / SECONDS_PER_HOUR;
        if (duration.getSeconds() % SECONDS_PER_HOUR != 0 || duration.getNano() != 0) {
            billableHours++;
        }

        return basePrice
                .multiply(multiplier)
                .multiply(BigDecimal.valueOf(billableHours))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
