package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.ParkedEventRequest;
import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.domain.model.ParkingSpot;
import br.com.estapar.parking.domain.model.Sector;
import br.com.estapar.parking.infrastructure.persistence.repository.GarageStateRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSpotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Service
public class ParkingSpotAssignmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParkingSpotAssignmentService.class);
    private static final Set<ParkingSessionStatus> ACTIVE_STATUSES =
            Set.of(ParkingSessionStatus.ENTERED, ParkingSessionStatus.PARKED);

    private final GarageInitializationState initializationState;
    private final GarageStateRepository garageStateRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingSpotRepository parkingSpotRepository;
    private final Clock clock;

    public ParkingSpotAssignmentService(
            GarageInitializationState initializationState,
            GarageStateRepository garageStateRepository,
            ParkingSessionRepository parkingSessionRepository,
            ParkingSpotRepository parkingSpotRepository,
            Clock clock
    ) {
        this.initializationState = initializationState;
        this.garageStateRepository = garageStateRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.parkingSpotRepository = parkingSpotRepository;
        this.clock = clock;
    }

    @Transactional
    public void processParked(ParkedEventRequest event) {
        ensureGarageIsReady();
        String plateReference = plateReference(event.licensePlate());
        LOGGER.info(
                "Processing PARKED plateRef={} latitude={} longitude={}",
                plateReference,
                event.latitude(),
                event.longitude()
        );

        garageStateRepository.findControlStateForUpdate()
                .orElseThrow(() -> new IllegalStateException("Garage state control row was not found"));

        ParkingSession session = parkingSessionRepository
                .findByLicensePlateAndStatusIn(event.licensePlate(), ACTIVE_STATUSES)
                .orElseThrow(() -> new ParkingSessionNotFoundException(
                        "License plate does not have an active parking session"
                ));

        ParkingSpot spot = parkingSpotRepository
                .findByLatitudeAndLongitude(event.latitude(), event.longitude())
                .orElseThrow(() -> new ParkingSpotNotFoundException(
                        "Parking spot was not found for the supplied coordinates"
                ));

        if (session.getStatus() == ParkingSessionStatus.PARKED) {
            if (session.isParkedAt(spot)) {
                LOGGER.info("PARKED delivery is idempotent plateRef={} spotId={}", plateReference, spot.getId());
                return;
            }
            throw new InvalidParkingSessionStateException(
                    "Parking session is already associated with a different parking spot"
            );
        }
        if (session.getStatus() != ParkingSessionStatus.ENTERED) {
            throw new InvalidParkingSessionStateException(
                    "Parking session is not in a state that allows parking spot assignment"
            );
        }
        if (spot.isOccupied()) {
            throw new ParkingSpotOccupiedException("Parking spot is already occupied");
        }

        Sector sector = spot.getSector();
        if (sector == null) {
            throw new InvalidParkingSessionStateException("Parking spot does not reference a persisted sector");
        }
        long parkedInSector = parkingSessionRepository.countBySectorIdAndStatus(
                sector.getId(),
                ParkingSessionStatus.PARKED
        );
        if (parkedInSector >= sector.getMaxCapacity()) {
            throw new SectorCapacityExceededException("Parking sector has reached its physical capacity");
        }

        Instant parkedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        session.assignParkingSpot(spot, parkedAt);
        spot.occupy();
        parkingSessionRepository.saveAndFlush(session);
        LOGGER.info(
                "PARKED accepted sessionId={} plateRef={} spotId={} sectorCode={}",
                session.getId(),
                plateReference,
                spot.getId(),
                sector.getCode()
        );
    }

    private void ensureGarageIsReady() {
        if (initializationState.getStatus() != GarageInitializationState.Status.READY) {
            throw new ParkingEventProcessingUnavailableException(
                    "Garage configuration is not ready for parking event processing"
            );
        }
    }

    private String plateReference(String licensePlate) {
        return Integer.toUnsignedString(licensePlate.hashCode(), 16);
    }
}
