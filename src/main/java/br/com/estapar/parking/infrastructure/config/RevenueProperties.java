package br.com.estapar.parking.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties(prefix = "revenue")
public class RevenueProperties {

    private ZoneId zoneId = ZoneId.of("America/Sao_Paulo");

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }
}
