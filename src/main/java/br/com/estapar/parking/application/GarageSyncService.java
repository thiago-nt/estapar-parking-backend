package br.com.estapar.parking.application;

import br.com.estapar.parking.domain.model.ParkingSpot;
import br.com.estapar.parking.domain.model.Sector;
import br.com.estapar.parking.infrastructure.persistence.repository.GarageStateRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSpotRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.SectorRepository;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageConfigurationResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSectorResponse;
import br.com.estapar.parking.infrastructure.simulator.dto.GarageSpotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class GarageSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GarageSyncService.class);

    private final GarageConfigurationValidator validator;
    private final SectorRepository sectorRepository;
    private final ParkingSpotRepository parkingSpotRepository;
    private final GarageStateRepository garageStateRepository;

    public GarageSyncService(
            GarageConfigurationValidator validator,
            SectorRepository sectorRepository,
            ParkingSpotRepository parkingSpotRepository,
            GarageStateRepository garageStateRepository
    ) {
        this.validator = validator;
        this.sectorRepository = sectorRepository;
        this.parkingSpotRepository = parkingSpotRepository;
        this.garageStateRepository = garageStateRepository;
    }

    @Transactional
    public GarageSyncResult synchronize(GarageConfigurationResponse configuration) {
        validator.validate(configuration);
        garageStateRepository.findControlStateForUpdate()
                .orElseThrow(() -> new IllegalStateException("Garage state control row was not found"));

        Map<String, Sector> sectorsByCode = new HashMap<>();
        int sectorsInserted = 0;
        int sectorsUpdated = 0;

        for (GarageSectorResponse sectorResponse : configuration.garage()) {
            Sector sector = sectorRepository.findByCode(sectorResponse.sector()).orElse(null);
            if (sector == null) {
                sector = sectorRepository.save(new Sector(
                        sectorResponse.sector(),
                        sectorResponse.basePrice(),
                        sectorResponse.maxCapacity()
                ));
                sectorsInserted++;
            } else if (!sector.hasConfiguration(sectorResponse.basePrice(), sectorResponse.maxCapacity())) {
                sector.updateConfiguration(sectorResponse.basePrice(), sectorResponse.maxCapacity());
                sectorsUpdated++;
            }
            sectorsByCode.put(normalizeSectorCode(sectorResponse.sector()), sector);
        }

        int spotsInserted = 0;
        int spotsUpdated = 0;
        for (GarageSpotResponse spotResponse : configuration.spots()) {
            Sector sector = sectorsByCode.get(normalizeSectorCode(spotResponse.sector()));
            ParkingSpot conflictingLocation = parkingSpotRepository
                    .findByLatitudeAndLongitude(spotResponse.latitude(), spotResponse.longitude())
                    .filter(found -> !found.getId().equals(spotResponse.id()))
                    .orElse(null);
            if (conflictingLocation != null) {
                throw new GarageSyncConflictException("Garage spot location conflicts with another persisted spot");
            }

            ParkingSpot spot = parkingSpotRepository.findById(spotResponse.id()).orElse(null);
            if (spot == null) {
                parkingSpotRepository.save(new ParkingSpot(
                        spotResponse.id(),
                        sector,
                        spotResponse.latitude(),
                        spotResponse.longitude()
                ));
                spotsInserted++;
            } else if (!spot.hasMetadata(sector, spotResponse.latitude(), spotResponse.longitude())) {
                if (spot.isOccupied()) {
                    LOGGER.warn("Garage synchronization rejected metadata changes for occupied spot id={}", spot.getId());
                    throw new GarageSyncConflictException(
                            "Occupied garage spot metadata cannot be changed during synchronization"
                    );
                }
                spot.updateMetadata(sector, spotResponse.latitude(), spotResponse.longitude());
                spotsUpdated++;
            }
        }

        return new GarageSyncResult(
                configuration.garage().size(),
                configuration.spots().size(),
                sectorsInserted,
                sectorsUpdated,
                spotsInserted,
                spotsUpdated
        );
    }

    private String normalizeSectorCode(String code) {
        return code.toUpperCase(Locale.ROOT);
    }
}
