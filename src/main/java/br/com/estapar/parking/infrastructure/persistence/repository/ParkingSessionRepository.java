package br.com.estapar.parking.infrastructure.persistence.repository;

import br.com.estapar.parking.domain.model.ParkingSession;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface ParkingSessionRepository extends JpaRepository<ParkingSession, Long> {

    Optional<ParkingSession> findByLicensePlateAndStatusIn(
            String licensePlate,
            Collection<ParkingSessionStatus> statuses
    );

    long countByStatusIn(Collection<ParkingSessionStatus> statuses);

    long countBySectorIdAndStatus(Long sectorId, ParkingSessionStatus status);

    boolean existsByLicensePlateAndStatusAndExitTime(
            String licensePlate,
            ParkingSessionStatus status,
            Instant exitTime
    );

    @Query("""
            select sum(session.amount)
            from ParkingSession session
            where session.sector.code = :sectorCode
              and session.status = :status
              and session.exitTime >= :startInclusive
              and session.exitTime < :endExclusive
            """)
    BigDecimal sumAmountBySectorAndStatusAndExitTimeRange(
            @Param("sectorCode") String sectorCode,
            @Param("status") ParkingSessionStatus status,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive
    );
}
