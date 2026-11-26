package br.com.estapar.parking.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PricingServiceTest {

    private static final Instant ENTRY_TIME = Instant.parse("2025-01-01T12:00:00Z");

    private final PricingService pricingService = new PricingService();

    @ParameterizedTest
    @MethodSource("durationScenarios")
    void shouldApplyToleranceAndRoundDurationUpWithoutTruncatingFractions(
            Duration duration,
            String expectedAmount
    ) {
        BigDecimal amount = pricingService.calculate(
                ENTRY_TIME,
                ENTRY_TIME.plus(duration),
                new BigDecimal("10.00"),
                new BigDecimal("1.00")
        );

        assertEquals(new BigDecimal(expectedAmount), amount);
        assertEquals(2, amount.scale());
    }

    @ParameterizedTest
    @CsvSource({
            "0.90, 18.00",
            "1.00, 20.00",
            "1.10, 22.00",
            "1.25, 25.00"
    })
    void shouldApplySupportedMultipliers(String multiplier, String expectedAmount) {
        BigDecimal amount = pricingService.calculate(
                ENTRY_TIME,
                ENTRY_TIME.plus(Duration.ofMinutes(90)),
                new BigDecimal("10.00"),
                new BigDecimal(multiplier)
        );

        assertEquals(new BigDecimal(expectedAmount), amount);
    }

    @Test
    void shouldRoundOnlyFinalMonetaryResultUsingHalfUp() {
        BigDecimal amount = pricingService.calculate(
                ENTRY_TIME,
                ENTRY_TIME.plus(Duration.ofMinutes(31)),
                new BigDecimal("10.005"),
                BigDecimal.ONE
        );

        assertEquals(new BigDecimal("10.01"), amount);
    }

    @Test
    void shouldRejectExitBeforeEntry() {
        assertThrows(
                IllegalArgumentException.class,
                () -> pricingService.calculate(ENTRY_TIME, ENTRY_TIME.minusMillis(1), BigDecimal.TEN, BigDecimal.ONE)
        );
    }

    @Test
    void shouldRejectNegativeBasePrice() {
        assertThrows(
                IllegalArgumentException.class,
                () -> pricingService.calculate(ENTRY_TIME, ENTRY_TIME, new BigDecimal("-0.01"), BigDecimal.ONE)
        );
    }

    @ParameterizedTest
    @CsvSource({"0.00", "-0.01"})
    void shouldRejectNonPositiveMultiplier(String multiplier) {
        assertThrows(
                IllegalArgumentException.class,
                () -> pricingService.calculate(
                        ENTRY_TIME,
                        ENTRY_TIME,
                        BigDecimal.TEN,
                        new BigDecimal(multiplier)
                )
        );
    }

    @Test
    void shouldRejectNullRequiredArguments() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> pricingService.calculate(null, ENTRY_TIME, BigDecimal.TEN, BigDecimal.ONE)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> pricingService.calculate(ENTRY_TIME, null, BigDecimal.TEN, BigDecimal.ONE)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> pricingService.calculate(ENTRY_TIME, ENTRY_TIME, null, BigDecimal.ONE)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> pricingService.calculate(ENTRY_TIME, ENTRY_TIME, BigDecimal.TEN, null)
                )
        );
    }

    private static Stream<Arguments> durationScenarios() {
        return Stream.of(
                Arguments.of(Duration.ZERO, "0.00"),
                Arguments.of(Duration.ofMinutes(15), "0.00"),
                Arguments.of(Duration.ofMinutes(30), "0.00"),
                Arguments.of(Duration.ofMinutes(30).plusMillis(1), "10.00"),
                Arguments.of(Duration.ofMinutes(31), "10.00"),
                Arguments.of(Duration.ofMinutes(60), "10.00"),
                Arguments.of(Duration.ofMinutes(60).plusMillis(1), "20.00"),
                Arguments.of(Duration.ofMinutes(61), "20.00"),
                Arguments.of(Duration.ofMinutes(90), "20.00"),
                Arguments.of(Duration.ofMinutes(120), "20.00"),
                Arguments.of(Duration.ofMinutes(121), "30.00")
        );
    }
}
