package com.lcs.star.bpa.workpack;

import com.lcs.star.bpa.api.generated.model.WorkPackListRow;
import com.lcs.star.bpa.persistence.WorkPackListRepository.Row;

/** A row of the Work Packs list (BPA-72) as the contract's {@code WorkPackListRow}; the Work Pack detail's summary (BPA-73) is the same row. */
public final class WorkPackListMapper {

    private WorkPackListMapper() {
    }

    public static WorkPackListRow toRow(Row r) {
        return new WorkPackListRow(r.workpackNumber(), r.activityRef(), r.drawingRef(), r.departmentRef(), r.projectRef(), WorkPackListRow.StatusEnum.fromValue(r.status()))
                .assignmentState(r.assignmentState() == null ? null : WorkPackListRow.AssignmentStateEnum.fromValue(r.assignmentState()))
                .plannedStartDate(r.plannedStartDate()).plannedEndDate(r.plannedEndDate()).approvedPct(r.approvedPct())
                .approvedWeekEnding(r.approvedWeekEnding()).earnedManhours(r.earnedManhours());
    }
}
