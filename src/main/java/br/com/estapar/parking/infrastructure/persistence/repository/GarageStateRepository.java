package br.com.estapar.parking.infrastructure.persistence.repository;

import br.com.estapar.parking.domain.model.GarageState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface GarageStateRepository extends JpaRepository<GarageState, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from GarageState state where state.id = 1")
    Optional<GarageState> findControlStateForUpdate();
}
