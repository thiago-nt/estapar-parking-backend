package br.com.estapar.parking.infrastructure.persistence.repository;

import br.com.estapar.parking.domain.model.ParkingSpot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ParkingSpotRepository extends JpaRepository<ParkingSpot, Long> {

    Optional<ParkingSpot> findByLatitudeAndLongitude(BigDecimal latitude, BigDecimal longitude);

    List<ParkingSpot> findBySectorId(Long sectorId);
}
