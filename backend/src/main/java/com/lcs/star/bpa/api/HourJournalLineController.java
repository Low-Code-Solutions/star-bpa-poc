package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.HourJournalLinesApi;
import com.lcs.star.bpa.api.generated.model.HourJournalLine;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * STUB for the red run (L15): BPA-14's read endpoint is not built yet, so it answers an empty list for every Work Pack. Replaced in the
 * build step.
 */
@RestController
public class HourJournalLineController implements HourJournalLinesApi {

    @Override
    public ResponseEntity<List<HourJournalLine>> listHourJournalLines(String workpackNumber) {
        return ResponseEntity.ok(List.of());
    }
}
