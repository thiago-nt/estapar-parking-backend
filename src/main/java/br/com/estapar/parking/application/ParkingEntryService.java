package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.EntryEventRequest;
import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.infrastructure.persistence.repository.GarageStateRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.SectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Service
public class ParkingEntryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParkingEntryService.class);
    private static final Set<ParkingSessionStatus> ACTIVE_STATUSES =
            Set.of(ParkingSessionStatus.ENTERED, ParkingSessionStatus.PARKED);

    private final GarageInitializationState initializationState;
    private final GarageStateRepository garageStateRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final SectorRepository sectorRepository;

    public ParkingEntryService(
            GarageInitializationState initializationState,
            GarageStateRepository garageStateRepository,
            ParkingSessionRepository parkingSessionRepository,
            SectorRepository sectorRepository
    ) {
        this.initializationState = initializationState;
        this.garageStateRepository = garageStateRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.sectorRepository = sectorRepository;
    }

    @Transactional
    public void processEntry(EntryEventRequest event) {
        ensureGarageIsReady();

        String plateReference = plateReference(event.licensePlate());
        Instant entryTime = event.entryTime().truncatedTo(ChronoUnit.MICROS);
        LOGGER.info("Processing ENTRY plateRef={} entryTime={}", plateReference, entryTime);

        garageStateRepository.findControlStateForUpdate()
                .orElseThrow(() -> new IllegalStateException("Garage state control row was not found"));

        ParkingSession activeSession = parkingSessionRepository
                .findByLicensePlateAndStatusIn(event.licensePlate(), ACTIVE_STATUSES)
                .orElse(null);

        if (activeSession != null) {
            if (activeSession.getEntryTime().equals(entryTime)) {
                LOGGER.info("ENTRY delivery is idempotent plateRef={} entryTime={}", plateReference, entryTime);
                return;
            }
            LOGGER.warn(
                    "ENTRY conflicts with an active parking session plateRef={} existingEntryTime={} receivedEntryTime={}",
                    plateReference,
                    activeSession.getEntryTime(),
                    entryTime
            );
            throw new ActiveParkingSessionConflictException(
                    "License plate already has an active parking session with a different entry time"
            );
        }

        long capacity = sectorRepository.sumMaxCapacity();
        long activeSessions = parkingSessionRepository.countByStatusIn(ACTIVE_STATUSES);
        if (activeSessions >= capacity) {
            LOGGER.warn(
                    "ENTRY rejected because garage is full plateRef={} activeSessions={} capacity={}",
                    plateReference,
                    activeSessions,
                    capacity
            );
            throw new GarageFullException("Garage has no available entry capacity");
        }

        ParkingSession session = new ParkingSession(event.licensePlate(), entryTime);
        parkingSessionRepository.saveAndFlush(session);
        LOGGER.info(
                "ENTRY accepted sessionId={} plateRef={} activeSessions={} capacity={}",
                session.getId(),
                plateReference,
                activeSessions + 1,
                capacity
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
