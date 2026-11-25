CREATE TABLE sector (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(20) NOT NULL,
    base_price DECIMAL(12, 2) NOT NULL,
    max_capacity INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_sector PRIMARY KEY (id),
    CONSTRAINT uk_sector_code UNIQUE (code),
    CONSTRAINT chk_sector_base_price_non_negative CHECK (base_price >= 0),
    CONSTRAINT chk_sector_max_capacity_positive CHECK (max_capacity > 0)
) ENGINE = InnoDB;

CREATE TABLE parking_spot (
    id BIGINT NOT NULL,
    sector_id BIGINT NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_parking_spot PRIMARY KEY (id),
    CONSTRAINT fk_parking_spot_sector FOREIGN KEY (sector_id) REFERENCES sector (id),
    CONSTRAINT chk_parking_spot_status CHECK (status IN ('AVAILABLE', 'OCCUPIED')),
    CONSTRAINT uk_parking_spot_location UNIQUE (latitude, longitude),
    INDEX idx_parking_spot_sector_id (sector_id),
    INDEX idx_parking_spot_status (status)
) ENGINE = InnoDB;

CREATE TABLE parking_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    license_plate VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    entry_time DATETIME(6) NOT NULL,
    parked_at DATETIME(6) NULL,
    exit_time DATETIME(6) NULL,
    parking_spot_id BIGINT NULL,
    sector_id BIGINT NULL,
    base_price_at_parking DECIMAL(12, 2) NULL,
    applied_multiplier DECIMAL(4, 2) NULL,
    amount DECIMAL(12, 2) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_license_plate VARCHAR(20) GENERATED ALWAYS AS (
        CASE WHEN status IN ('ENTERED', 'PARKED') THEN license_plate ELSE NULL END
    ) VIRTUAL,
    active_parking_spot_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'PARKED' THEN parking_spot_id ELSE NULL END
    ) VIRTUAL,
    CONSTRAINT pk_parking_session PRIMARY KEY (id),
    CONSTRAINT fk_parking_session_parking_spot FOREIGN KEY (parking_spot_id) REFERENCES parking_spot (id),
    CONSTRAINT fk_parking_session_sector FOREIGN KEY (sector_id) REFERENCES sector (id),
    CONSTRAINT chk_parking_session_status CHECK (status IN ('ENTERED', 'PARKED', 'EXITED')),
    INDEX idx_parking_session_license_plate (license_plate),
    INDEX idx_parking_session_status (status),
    INDEX idx_parking_session_sector_exit_time (sector_id, exit_time),
    INDEX idx_parking_session_parking_spot_id (parking_spot_id),
    UNIQUE INDEX uk_parking_session_active_license_plate (active_license_plate),
    UNIQUE INDEX uk_parking_session_active_parking_spot (active_parking_spot_id)
) ENGINE = InnoDB;

CREATE TABLE garage_state (
    id INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_garage_state PRIMARY KEY (id)
) ENGINE = InnoDB;

INSERT INTO garage_state (id, version) VALUES (1, 0);
