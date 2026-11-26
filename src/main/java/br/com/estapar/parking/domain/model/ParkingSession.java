package br.com.estapar.parking.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "parking_session",
        indexes = {
                @Index(name = "idx_parking_session_license_plate", columnList = "license_plate"),
                @Index(name = "idx_parking_session_status", columnList = "status"),
                @Index(name = "idx_parking_session_sector_exit_time", columnList = "sector_id, exit_time"),
                @Index(name = "idx_parking_session_parking_spot_id", columnList = "parking_spot_id")
        }
)
public class ParkingSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "license_plate", nullable = false, length = 20)
    private String licensePlate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParkingSessionStatus status;

    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    @Column(name = "parked_at")
    private Instant parkedAt;

    @Column(name = "exit_time")
    private Instant exitTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_spot_id")
    private ParkingSpot parkingSpot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sector_id")
    private Sector sector;

    @Column(name = "base_price_at_parking", precision = 12, scale = 2)
    private BigDecimal basePriceAtParking;

    @Column(name = "applied_multiplier", precision = 4, scale = 2)
    private BigDecimal appliedMultiplier;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    protected ParkingSession() {
    }

    public ParkingSession(String licensePlate, Instant entryTime) {
        this.licensePlate = Objects.requireNonNull(licensePlate, "licensePlate must not be null");
        this.entryTime = Objects.requireNonNull(entryTime, "entryTime must not be null");
        this.status = ParkingSessionStatus.ENTERED;
    }

    public Long getId() {
        return id;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public ParkingSessionStatus getStatus() {
        return status;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public Instant getParkedAt() {
        return parkedAt;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public ParkingSpot getParkingSpot() {
        return parkingSpot;
    }

    public Sector getSector() {
        return sector;
    }

    public BigDecimal getBasePriceAtParking() {
        return basePriceAtParking;
    }

    public BigDecimal getAppliedMultiplier() {
        return appliedMultiplier;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isParkedAt(ParkingSpot parkingSpot) {
        return status == ParkingSessionStatus.PARKED
                && this.parkingSpot != null
                && Objects.equals(this.parkingSpot.getId(), parkingSpot.getId());
    }

    public void assignParkingSpot(ParkingSpot parkingSpot, Instant parkedAt, BigDecimal appliedMultiplier) {
        if (status != ParkingSessionStatus.ENTERED) {
            throw new IllegalStateException("Only an entered session can be assigned to a parking spot");
        }
        ParkingSpot assignedSpot = Objects.requireNonNull(parkingSpot, "parkingSpot must not be null");
        Sector assignedSector = Objects.requireNonNull(
                assignedSpot.getSector(),
                "parkingSpot sector must not be null"
        );
        Instant assignmentTime = Objects.requireNonNull(parkedAt, "parkedAt must not be null");
        BigDecimal pricingMultiplier = Objects.requireNonNull(
                appliedMultiplier,
                "appliedMultiplier must not be null"
        );
        if (pricingMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("appliedMultiplier must be greater than zero");
        }

        this.parkingSpot = assignedSpot;
        this.sector = assignedSector;
        this.parkedAt = assignmentTime;
        this.basePriceAtParking = sector.getBasePrice();
        this.appliedMultiplier = pricingMultiplier;
        this.status = ParkingSessionStatus.PARKED;
    }

    public void finish(Instant exitTime, BigDecimal amount) {
        if (status != ParkingSessionStatus.PARKED) {
            throw new IllegalStateException("Only a parked session can be finished");
        }
        Instant finishedAt = Objects.requireNonNull(exitTime, "exitTime must not be null");
        if (finishedAt.isBefore(entryTime)) {
            throw new IllegalArgumentException("exitTime must not be before entryTime");
        }
        BigDecimal finalAmount = Objects.requireNonNull(amount, "amount must not be null");
        if (finalAmount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }

        this.exitTime = finishedAt;
        this.amount = finalAmount;
        this.status = ParkingSessionStatus.EXITED;
    }
}
