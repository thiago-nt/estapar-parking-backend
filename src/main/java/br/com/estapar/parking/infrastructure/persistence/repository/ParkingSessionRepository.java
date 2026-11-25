package br.com.estapar.parking.infrastructure.persistence.repository;

import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface ParkingSessionRepository extends JpaRepository<ParkingSession, Long> {

    Optional<ParkingSession> findByLicensePlateAndStatusIn(
            String licensePlate,
            Collection<ParkingSessionStatus> statuses
    );

    long countByStatusIn(Collection<ParkingSessionStatus> statuses);

    long countBySectorIdAndStatus(Long sectorId, ParkingSessionStatus status);
}
