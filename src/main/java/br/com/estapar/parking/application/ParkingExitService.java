package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.ExitEventRequest;
import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.domain.model.ParkingSpot;
import br.com.estapar.parking.domain.service.PricingService;
import br.com.estapar.parking.infrastructure.persistence.repository.GarageStateRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSpotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Service
public class ParkingExitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParkingExitService.class);
    private static final Set<ParkingSessionStatus> ACTIVE_STATUSES =
            Set.of(ParkingSessionStatus.ENTERED, ParkingSessionStatus.PARKED);

    private final GarageInitializationState initializationState;
    private final GarageStateRepository garageStateRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingSpotRepository parkingSpotRepository;
    private final PricingService pricingService;

    public ParkingExitService(
            GarageInitializationState initializationState,
            GarageStateRepository garageStateRepository,
            ParkingSessionRepository parkingSessionRepository,
            ParkingSpotRepository parkingSpotRepository,
            PricingService pricingService
    ) {
        this.initializationState = initializationState;
        this.garageStateRepository = garageStateRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.parkingSpotRepository = parkingSpotRepository;
        this.pricingService = pricingService;
    }

    @Transactional
    public void processExit(ExitEventRequest event) {
        ensureGarageIsReady();
        Instant exitTime = event.exitTime().truncatedTo(ChronoUnit.MICROS);
        String plateReference = plateReference(event.licensePlate());
        LOGGER.info("Processing EXIT plateRef={} exitTime={}", plateReference, exitTime);

        garageStateRepository.findControlStateForUpdate()
                .orElseThrow(() -> new IllegalStateException("Garage state control row was not found"));

        boolean duplicateExit = parkingSessionRepository.existsByLicensePlateAndStatusAndExitTime(
                event.licensePlate(),
                ParkingSessionStatus.EXITED,
                exitTime
        );
        if (duplicateExit) {
            LOGGER.info("EXIT delivery is idempotent plateRef={} exitTime={}", plateReference, exitTime);
            return;
        }

        ParkingSession session = parkingSessionRepository
                .findByLicensePlateAndStatusIn(event.licensePlate(), ACTIVE_STATUSES)
                .orElseThrow(() -> new ParkingSessionNotFoundException(
                        "License plate does not have a parking session applicable to this exit"
                ));

        if (exitTime.isBefore(session.getEntryTime())) {
            throw new InvalidParkingSessionStateException("Exit time must not be before session entry time");
        }
        if (session.getStatus() != ParkingSessionStatus.PARKED) {
            throw new InvalidParkingSessionStateException("Only a parked session can be finished");
        }

        BigDecimal basePrice = session.getBasePriceAtParking();
        BigDecimal multiplier = session.getAppliedMultiplier();
        if (basePrice == null || basePrice.signum() < 0 || multiplier == null || multiplier.signum() <= 0) {
            LOGGER.warn(
                    "EXIT rejected because session pricing is incomplete sessionId={} plateRef={}",
                    session.getId(),
                    plateReference
            );
            throw new IncompleteParkingSessionPricingException(
                    "Parked session does not have complete frozen pricing data"
            );
        }

        ParkingSpot spot = session.getParkingSpot();
        if (spot == null || session.getSector() == null || !spot.isOccupied()) {
            throw new ParkingSpotStateConflictException(
                    "Parked session does not reference a consistently occupied parking spot"
            );
        }

        BigDecimal amount = pricingService.calculate(
                session.getEntryTime(),
                exitTime,
                basePrice,
                multiplier
        );

        session.finish(exitTime, amount);
        parkingSessionRepository.saveAndFlush(session);
        spot.release();
        parkingSpotRepository.saveAndFlush(spot);
        LOGGER.info(
                "EXIT accepted sessionId={} plateRef={} spotId={} amount={}",
                session.getId(),
                plateReference,
                spot.getId(),
                amount
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
