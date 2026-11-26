package br.com.estapar.parking.application;

import br.com.estapar.parking.api.dto.RevenueResponse;
import br.com.estapar.parking.domain.model.ParkingSessionStatus;
import br.com.estapar.parking.infrastructure.config.RevenueProperties;
import br.com.estapar.parking.infrastructure.persistence.repository.ParkingSessionRepository;
import br.com.estapar.parking.infrastructure.persistence.repository.SectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

@Service
public class RevenueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RevenueService.class);
    private static final String CURRENCY = "BRL";

    private final GarageInitializationState initializationState;
    private final SectorRepository sectorRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final Clock clock;
    private final ZoneId revenueZoneId;

    public RevenueService(
            GarageInitializationState initializationState,
            SectorRepository sectorRepository,
            ParkingSessionRepository parkingSessionRepository,
            Clock clock,
            RevenueProperties revenueProperties
    ) {
        this.initializationState = initializationState;
        this.sectorRepository = sectorRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.clock = clock;
        this.revenueZoneId = revenueProperties.getZoneId();
    }

    @Transactional(readOnly = true)
    public RevenueResponse getRevenue(LocalDate date, String sectorCode) {
        String normalizedSectorCode = normalizeSectorCode(sectorCode);
        ensureGarageIsReady(normalizedSectorCode);
        sectorRepository.findByCode(normalizedSectorCode)
                .orElseThrow(() -> {
                    LOGGER.warn("Revenue query rejected because sector does not exist sector={}", normalizedSectorCode);
                    return new SectorNotFoundException("Sector was not found: " + normalizedSectorCode);
                });

        Instant queriedAt = clock.instant();
        LocalDate currentDate = LocalDate.ofInstant(queriedAt, revenueZoneId);
        LocalDate queryDate = date == null ? currentDate : date;
        BigDecimal amount;

        if (queryDate.isAfter(currentDate)) {
            amount = monetary(BigDecimal.ZERO);
        } else {
            Instant startInclusive = queryDate.atStartOfDay(revenueZoneId).toInstant();
            Instant endExclusive = queryDate.plusDays(1).atStartOfDay(revenueZoneId).toInstant();
            if (queryDate.equals(currentDate) && queriedAt.isBefore(endExclusive)) {
                endExclusive = queriedAt;
            }
            amount = monetary(parkingSessionRepository.sumAmountBySectorAndStatusAndExitTimeRange(
                    normalizedSectorCode,
                    ParkingSessionStatus.EXITED,
                    startInclusive,
                    endExclusive
            ));
        }

        LOGGER.info(
                "Revenue query completed sector={} date={} zoneId={} amount={}",
                normalizedSectorCode,
                queryDate,
                revenueZoneId,
                amount
        );
        return new RevenueResponse(amount, CURRENCY, queriedAt);
    }

    private String normalizeSectorCode(String sectorCode) {
        if (sectorCode == null || sectorCode.isBlank()) {
            LOGGER.warn("Revenue query rejected because sector is missing");
            throw new InvalidRevenueRequestException("Sector is required");
        }
        return sectorCode.trim().toUpperCase(Locale.ROOT);
    }

    private void ensureGarageIsReady(String sectorCode) {
        if (initializationState.getStatus() != GarageInitializationState.Status.READY) {
            LOGGER.warn(
                    "Revenue query unavailable because garage is not ready sector={} status={}",
                    sectorCode,
                    initializationState.getStatus()
            );
            throw new ParkingEventProcessingUnavailableException(
                    "Garage configuration is not ready for revenue queries"
            );
        }
    }

    private BigDecimal monetary(BigDecimal amount) {
        BigDecimal nonNullAmount = amount == null ? BigDecimal.ZERO : amount;
        return nonNullAmount.setScale(2, RoundingMode.HALF_EVEN);
    }
}
