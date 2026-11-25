package br.com.estapar.parking.application;

import br.com.estapar.parking.infrastructure.simulator.dto.GarageConfigurationResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSectorResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSpotResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class GarageConfigurationValidator {

    private static final BigDecimal MIN_LATITUDE = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
    private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

    public void validate(GarageConfigurationResponse configuration) {
        if (configuration == null) {
            throw new InvalidGarageConfigurationException("Garage configuration must not be null");
        }
        if (configuration.garage() == null) {
            throw new InvalidGarageConfigurationException("Garage sector list is required");
        }
        if (configuration.spots() == null) {
            throw new InvalidGarageConfigurationException("Garage spot list is required");
        }

        Set<String> sectorCodes = validateSectors(configuration);
        validateSpots(configuration, sectorCodes);
    }

    private Set<String> validateSectors(GarageConfigurationResponse configuration) {
        Set<String> sectorCodes = new HashSet<>();
        for (GarageSectorResponse sector : configuration.garage()) {
            if (sector == null) {
                throw new InvalidGarageConfigurationException("Garage sector must not be null");
            }
            String normalizedCode = normalizeSectorCode(sector.sector());
            if (!sectorCodes.add(normalizedCode)) {
                throw new InvalidGarageConfigurationException("Garage sector codes must be unique");
            }
            if (sector.basePrice() == null || sector.basePrice().signum() < 0) {
                throw new InvalidGarageConfigurationException("Garage sector base price must be non-negative");
            }
            if (sector.maxCapacity() == null || sector.maxCapacity() <= 0) {
                throw new InvalidGarageConfigurationException("Garage sector capacity must be positive");
            }
        }
        return sectorCodes;
    }

    private void validateSpots(GarageConfigurationResponse configuration, Set<String> sectorCodes) {
        Set<Long> spotIds = new HashSet<>();
        Set<Coordinate> coordinates = new HashSet<>();

        for (GarageSpotResponse spot : configuration.spots()) {
            if (spot == null) {
                throw new InvalidGarageConfigurationException("Garage spot must not be null");
            }
            if (spot.id() == null || spot.id() <= 0 || !spotIds.add(spot.id())) {
                throw new InvalidGarageConfigurationException("Garage spot IDs must be positive and unique");
            }
            if (!sectorCodes.contains(normalizeSectorCode(spot.sector()))) {
                throw new InvalidGarageConfigurationException("Garage spot references an unknown sector");
            }
            validateCoordinate(spot.latitude(), MIN_LATITUDE, MAX_LATITUDE, "latitude");
            validateCoordinate(spot.longitude(), MIN_LONGITUDE, MAX_LONGITUDE, "longitude");
            if (!coordinates.add(new Coordinate(spot.latitude(), spot.longitude()))) {
                throw new InvalidGarageConfigurationException("Garage spot coordinates must be unique");
            }
        }
    }

    private String normalizeSectorCode(String code) {
        if (code == null || code.isBlank() || code.length() > 20 || !code.equals(code.trim())) {
            throw new InvalidGarageConfigurationException("Garage sector code is invalid");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    private void validateCoordinate(BigDecimal value, BigDecimal minimum, BigDecimal maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new InvalidGarageConfigurationException("Garage spot " + name + " is invalid");
        }
    }

    private record Coordinate(BigDecimal latitude, BigDecimal longitude) {

        private Coordinate {
            latitude = latitude.stripTrailingZeros();
            longitude = longitude.stripTrailingZeros();
        }
    }
}
