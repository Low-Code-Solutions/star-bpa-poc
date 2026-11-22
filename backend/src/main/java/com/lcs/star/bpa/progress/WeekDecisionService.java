package com.lcs.star.bpa.progress;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The Project Manager's decision on a week (BPA-66; E2-19; plan section 1). COMPILE STUB for the red run: the real record step comes in the green commit.
 */
@Service
public class WeekDecisionService {

    public void record(UUID periodId, String decision, String target, String reason, String completedBy) {
        throw new UnsupportedOperationException("BPA-66: not built yet");
    }
}
