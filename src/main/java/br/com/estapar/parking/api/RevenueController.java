package br.com.estapar.parking.api;

import br.com.estapar.parking.api.dto.RevenueRequest;
import br.com.estapar.parking.api.dto.RevenueResponse;
import br.com.estapar.parking.application.InvalidRevenueRequestException;
import br.com.estapar.parking.application.RevenueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class RevenueController {

    private final RevenueService revenueService;

    public RevenueController(RevenueService revenueService) {
        this.revenueService = revenueService;
    }

    @GetMapping("/revenue")
    public RevenueResponse getRevenue(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String sector,
            @RequestBody(required = false) RevenueRequest body
    ) {
        LocalDate bodyDate = body == null ? null : body.date();
        String bodySector = body == null ? null : body.sector();
        if (date != null && bodyDate != null && !date.equals(bodyDate)) {
            throw new InvalidRevenueRequestException("Query and body date values conflict");
        }
        if (sector != null && bodySector != null && !sameSector(sector, bodySector)) {
            throw new InvalidRevenueRequestException("Query and body sector values conflict");
        }

        LocalDate effectiveDate = date != null ? date : bodyDate;
        String effectiveSector = sector != null ? sector : bodySector;
        return revenueService.getRevenue(effectiveDate, effectiveSector);
    }

    private boolean sameSector(String querySector, String bodySector) {
        return querySector.trim().equalsIgnoreCase(bodySector.trim());
    }
}
