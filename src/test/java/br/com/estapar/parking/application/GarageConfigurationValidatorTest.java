package br.com.estapar.parking.application;

import br.com.estapar.parking.infrastructure.simulator.dto.GarageConfigurationResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSectorResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSpotResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GarageConfigurationValidatorTest {

    private final GarageConfigurationValidator validator = new GarageConfigurationValidator();

    @Test
    void shouldAcceptValidConfigurationWithoutMatchingCapacityAndSpotCount() {
        GarageConfigurationResponse configuration = new GarageConfigurationResponse(
                List.of(new GarageSectorResponse("A", BigDecimal.TEN, 100)),
                List.of(new GarageSpotResponse(
                        1L,
                        "A",
                        new BigDecimal("-23.561684"),
                        new BigDecimal("-46.655981")
                ))
        );

        assertDoesNotThrow(() -> validator.validate(configuration));
    }

    @Test
    void shouldRejectMissingLists() {
        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(null, List.of()))
        );
        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(List.of(), null))
        );
    }

    @Test
    void shouldRejectInvalidOrDuplicateSectors() {
        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(
                        List.of(
                                new GarageSectorResponse("A", BigDecimal.TEN, 1),
                                new GarageSectorResponse("a", BigDecimal.TEN, 1)
                        ),
                        List.of()
                ))
        );
        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(
                        List.of(new GarageSectorResponse("A", BigDecimal.valueOf(-1), 0)),
                        List.of()
                ))
        );
    }

    @Test
    void shouldRejectUnknownSectorAndDuplicateCoordinates() {
        GarageSectorResponse sector = new GarageSectorResponse("A", BigDecimal.TEN, 2);

        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(
                        List.of(sector),
                        List.of(new GarageSpotResponse(1L, "B", BigDecimal.ZERO, BigDecimal.ZERO))
                ))
        );
        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(
                        List.of(sector),
                        List.of(
                                new GarageSpotResponse(1L, "A", BigDecimal.ZERO, BigDecimal.ZERO),
                                new GarageSpotResponse(2L, "A", new BigDecimal("0.0"), new BigDecimal("0.00"))
                        )
                ))
        );
    }

    @Test
    void shouldRejectInvalidSpotIdAndCoordinates() {
        GarageSectorResponse sector = new GarageSectorResponse("A", BigDecimal.TEN, 1);

        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(
                        List.of(sector),
                        List.of(new GarageSpotResponse(0L, "A", BigDecimal.ZERO, BigDecimal.ZERO))
                ))
        );
        assertThrows(
                InvalidGarageConfigurationException.class,
                () -> validator.validate(new GarageConfigurationResponse(
                        List.of(sector),
                        List.of(new GarageSpotResponse(
                                1L,
                                "A",
                                BigDecimal.valueOf(91),
                                BigDecimal.valueOf(181)
                        ))
                ))
        );
    }
}
