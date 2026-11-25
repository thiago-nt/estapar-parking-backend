package br.com.estapar.parking.infrastructure.persistence.repository;

import br.com.estapar.parking.domain.model.Sector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SectorRepository extends JpaRepository<Sector, Long> {

    Optional<Sector> findByCode(String code);
}
