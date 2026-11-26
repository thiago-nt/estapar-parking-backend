package br.com.estapar.parking.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(
        name = "parking_spot",
        indexes = {
                @Index(name = "idx_parking_spot_sector_id", columnList = "sector_id"),
                @Index(name = "idx_parking_spot_status", columnList = "status"),
                @Index(name = "uk_parking_spot_location", columnList = "latitude, longitude", unique = true)
        }
)
public class ParkingSpot extends AuditableEntity {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sector_id", nullable = false)
    private Sector sector;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParkingSpotStatus status;

    protected ParkingSpot() {
    }

    public ParkingSpot(Long id, Sector sector, BigDecimal latitude, BigDecimal longitude) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sector = Objects.requireNonNull(sector, "sector must not be null");
        this.latitude = Objects.requireNonNull(latitude, "latitude must not be null");
        this.longitude = Objects.requireNonNull(longitude, "longitude must not be null");
        this.status = ParkingSpotStatus.AVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public Sector getSector() {
        return sector;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public ParkingSpotStatus getStatus() {
        return status;
    }

    public boolean hasMetadata(Sector sector, BigDecimal latitude, BigDecimal longitude) {
        return Objects.equals(this.sector.getId(), sector.getId())
                && this.latitude.compareTo(latitude) == 0
                && this.longitude.compareTo(longitude) == 0;
    }

    public boolean isOccupied() {
        return status == ParkingSpotStatus.OCCUPIED;
    }

    public void occupy() {
        if (isOccupied()) {
            throw new IllegalStateException("Parking spot is already occupied");
        }
        status = ParkingSpotStatus.OCCUPIED;
    }

    public void release() {
        if (!isOccupied()) {
            throw new IllegalStateException("Parking spot is not occupied");
        }
        status = ParkingSpotStatus.AVAILABLE;
    }

    public void updateMetadata(Sector sector, BigDecimal latitude, BigDecimal longitude) {
        this.sector = Objects.requireNonNull(sector, "sector must not be null");
        this.latitude = Objects.requireNonNull(latitude, "latitude must not be null");
        this.longitude = Objects.requireNonNull(longitude, "longitude must not be null");
    }
}
