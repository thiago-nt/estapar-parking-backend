package br.com.estapar.parking.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(
        name = "sector",
        uniqueConstraints = @UniqueConstraint(name = "uk_sector_code", columnNames = "code")
)
public class Sector extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity;

    protected Sector() {
    }

    public Sector(String code, BigDecimal basePrice, int maxCapacity) {
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.basePrice = Objects.requireNonNull(basePrice, "basePrice must not be null");
        this.maxCapacity = maxCapacity;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public boolean hasConfiguration(BigDecimal basePrice, int maxCapacity) {
        return this.basePrice.compareTo(basePrice) == 0 && this.maxCapacity == maxCapacity;
    }

    public void updateConfiguration(BigDecimal basePrice, int maxCapacity) {
        this.basePrice = Objects.requireNonNull(basePrice, "basePrice must not be null");
        this.maxCapacity = maxCapacity;
    }
}
