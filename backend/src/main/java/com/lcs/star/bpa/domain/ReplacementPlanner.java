package com.lcs.star.bpa.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The rule for replacing a Work Pack's Worker assignment (BPA-13; D35, D36, D38, D72), as one decision per Worker. It compares the Work
 * Pack's current assignments with the Workers the replacement file asks for, whose rows have all been accepted (a file with a rejected row
 * is not applied at all, so it never reaches this rule):
 * <ul>
 * <li>in the file and already assigned, hours changed: {@code REPLACED} (history old to new);</li>
 * <li>in the file and already assigned, hours the same (compared as numbers, so 40.00 equals 40): {@code UNCHANGED}, no history (data model 5.4);</li>
 * <li>in the file and not assigned: {@code ADDED} (history 0 to new);</li>
 * <li>assigned but not in the file, no hours loaded: {@code REMOVED} (history old to 0);</li>
 * <li>assigned but not in the file, hours loaded (D36): {@code KEPT_FLAGGED}, hours unchanged, no history.</li>
 * </ul>
 * The steps come in the file's order, then the Workers the file left out in their current order. Pure: no database, no process.
 */
public final class ReplacementPlanner {

    /** A Worker currently assigned to the Work Pack; {@code hoursLoaded} is true when a digital_timesheet_line with hours above 0 exists for the Worker and the Work Pack (D36). */
    public record Current(UUID workerId, String badgeNo, BigDecimal hours, boolean hoursLoaded) {
    }

    /** A Worker the replacement file asks for, with the hours already validated (two decimals). */
    public record Wanted(UUID workerId, String badgeNo, BigDecimal hours, String remarks) {
    }

    public enum Kind { ADDED, REPLACED, UNCHANGED, REMOVED, KEPT_FLAGGED }

    /** One decision per Worker: what happens to the assignment, with the hours before and after (0 for the absent side). */
    public record Step(Kind kind, UUID workerId, String badgeNo, BigDecimal oldHours, BigDecimal newHours) {
    }

    private ReplacementPlanner() {
    }

    public static List<Step> plan(List<Current> current, List<Wanted> wanted) {
        Map<UUID, Current> assigned = new HashMap<>();
        for (Current c : current) {
            assigned.put(c.workerId(), c);
        }
        List<Step> steps = new ArrayList<>();
        Set<UUID> inTheFile = new HashSet<>();
        for (Wanted w : wanted) {
            inTheFile.add(w.workerId());
            Current existing = assigned.get(w.workerId());
            if (existing == null) {
                steps.add(new Step(Kind.ADDED, w.workerId(), w.badgeNo(), BigDecimal.ZERO, w.hours()));
            } else if (existing.hours().compareTo(w.hours()) != 0) {
                steps.add(new Step(Kind.REPLACED, w.workerId(), w.badgeNo(), existing.hours(), w.hours()));
            } else {
                steps.add(new Step(Kind.UNCHANGED, w.workerId(), w.badgeNo(), existing.hours(), w.hours()));
            }
        }
        for (Current c : current) {
            if (inTheFile.contains(c.workerId())) {
                continue;
            }
            steps.add(c.hoursLoaded()
                    ? new Step(Kind.KEPT_FLAGGED, c.workerId(), c.badgeNo(), c.hours(), c.hours())
                    : new Step(Kind.REMOVED, c.workerId(), c.badgeNo(), c.hours(), BigDecimal.ZERO));
        }
        return steps;
    }
}
