package br.com.estapar.parking.infrastructure.persistence.repository;

import br.com.estapar.parking.domain.model.Sector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SectorRepository extends JpaRepository<Sector, Long> {

    Optional<Sector> findByCode(String code);

    @Query("select coalesce(sum(sector.maxCapacity), 0) from Sector sector")
    long sumMaxCapacity();
}
