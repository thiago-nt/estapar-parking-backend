package br.com.estapar.parking.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "garage_state")
public class GarageState extends AuditableEntity {

    @Id
    private Integer id;

    @Version
    @Column(nullable = false)
    private Long version;

    protected GarageState() {
    }

    public Integer getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }
}
