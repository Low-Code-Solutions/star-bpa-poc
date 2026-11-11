package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.BusinessDateApi;
import com.lcs.star.bpa.api.generated.model.BusinessDate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/business-date}: today in the business zone, from the injected clock and zone the validators use (D20, D28; 09 S9, ADR 0013), so
 * the portal checks dates against the same "today" as the server, including a QA business date (BPA-81). BPA-82.
 */
@RestController
public class BusinessDateController implements BusinessDateApi {

    private final Clock clock;
    private final ZoneId businessZone;

    public BusinessDateController(Clock clock, ZoneId businessZone) {
        this.clock = clock;
        this.businessZone = businessZone;
    }

    @Override
    public ResponseEntity<BusinessDate> getBusinessDate() {
        return ResponseEntity.ok(new BusinessDate(LocalDate.ofInstant(clock.instant(), businessZone), businessZone.getId()));
    }
}
