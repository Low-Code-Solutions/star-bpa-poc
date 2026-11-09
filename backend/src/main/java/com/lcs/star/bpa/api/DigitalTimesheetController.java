package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.TimesheetsApi;
import com.lcs.star.bpa.api.generated.model.DigitalTimesheet;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * STUB for the red run (L15): BPA-15's read endpoint is not built yet, so it answers an empty list for every Work Pack. Replaced in the
 * build step.
 */
@RestController
public class DigitalTimesheetController implements TimesheetsApi {

    @Override
    public ResponseEntity<List<DigitalTimesheet>> listDigitalTimesheets(String workpackNumber) {
        return ResponseEntity.ok(List.of());
    }
}
