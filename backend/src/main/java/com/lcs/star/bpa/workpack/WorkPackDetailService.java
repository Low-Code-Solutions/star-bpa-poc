package com.lcs.star.bpa.workpack;

import com.lcs.star.bpa.api.generated.model.WorkPackDetail;
import com.lcs.star.bpa.api.generated.model.WorkPackHistoryItem;
import com.lcs.star.bpa.api.generated.model.WorkPackHistoryItemNotice;
import com.lcs.star.bpa.api.generated.model.WorkPackHistoryPage;
import com.lcs.star.bpa.api.generated.model.WorkPackHourLine;
import com.lcs.star.bpa.api.generated.model.WorkPackHours;
import com.lcs.star.bpa.api.generated.model.WorkPackListRow;
import com.lcs.star.bpa.api.generated.model.WorkPackOverview;
import com.lcs.star.bpa.api.generated.model.WorkPackOverviewMaterialsInner;
import com.lcs.star.bpa.api.generated.model.WorkPackOverviewPhasesInner;
import com.lcs.star.bpa.api.generated.model.WorkPackOverviewPrint;
import com.lcs.star.bpa.api.generated.model.WorkPackOverviewSchedule;
import com.lcs.star.bpa.api.generated.model.WorkPackRetraction;
import com.lcs.star.bpa.api.generated.model.WorkPackWeek;
import com.lcs.star.bpa.api.generated.model.WorkPackWorker;
import com.lcs.star.bpa.persistence.WorkPackDetailRepository;
import com.lcs.star.bpa.persistence.WorkPackDetailRepository.Header;
import com.lcs.star.bpa.persistence.WorkPackListRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The Work Pack detail and its history (BPA-73; E2-23), read only: the summary is the Work Packs list's row (BPA-72), the rest comes from
 * {@link WorkPackDetailRepository}. A Work Pack with no hours or no progress gets empty lists (the screen says "nothing yet"). Nothing is written.
 */
@Service
public class WorkPackDetailService {

    private final WorkPackDetailRepository detail;
    private final WorkPackListRepository list;

    public WorkPackDetailService(WorkPackDetailRepository detail, WorkPackListRepository list) {
        this.detail = detail;
        this.list = list;
    }

    public Optional<Header> header(String workpackNumber) {
        return detail.header(workpackNumber);
    }

    public WorkPackDetail detail(String workpackNumber, Header header) {
        WorkPackListRow summary = list.list(new WorkPackListRepository.Filter(null, null, null, workpackNumber)).stream()
                .filter(r -> workpackNumber.equals(r.workpackNumber())).findFirst().map(WorkPackListMapper::toRow).orElseThrow();
        WorkPackOverview overview = new WorkPackOverview(header.completionDate(),
                detail.phases(header.id()).stream().map(p -> new WorkPackOverviewPhasesInner(WorkPackOverviewPhasesInner.PhaseEnum.fromValue(p.phase()), p.manhours())).toList(),
                detail.materials(header.id()).stream().map(m -> new WorkPackOverviewMaterialsInner(m.materialRef(), m.phase())).toList());
        detail.schedule(header.id()).ifPresent(s -> overview.setSchedule(new WorkPackOverviewSchedule(s.plannedStartDate(), s.plannedEndDate(), s.scheduledBy())));
        detail.printGeneratedAt(header.id()).ifPresent(at -> overview.setPrint(new WorkPackOverviewPrint(at)));

        List<WorkPackWorker> workers = detail.workers(header.id()).stream().map(w -> {
            WorkPackWorker worker = new WorkPackWorker(w.badgeNo(), w.workerName(), w.estimatedHours(), w.hoursLoadedNotRemoved()).role(w.role()).designation(w.designation())
                    .cbsCodeRef(w.cbsCodeRef());
            if (w.journalStatus() != null) {
                worker.setJournalStatus(WorkPackWorker.JournalStatusEnum.fromValue(w.journalStatus()));
            }
            if (w.timesheetStatus() != null) {
                worker.setTimesheetStatus(WorkPackWorker.TimesheetStatusEnum.fromValue(w.timesheetStatus()));
            }
            return worker;
        }).toList();

        List<WorkPackHourLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (WorkPackDetailRepository.HourLine l : detail.hourLines(header.id())) {
            WorkPackHourLine line = new WorkPackHourLine(l.badgeNo(), l.workDate(), l.phase(), l.hours(), WorkPackHourLine.StatusEnum.fromValue(l.status()));
            if (l.step() != null) {
                line.setStep(WorkPackHourLine.StepEnum.fromValue(l.step()));
            }
            if (l.entrySource() != null) {
                line.setEntrySource(WorkPackHourLine.EntrySourceEnum.fromValue(l.entrySource()));
            }
            lines.add(line);
            if (!"Rejected".equals(l.status())) {
                total = total.add(l.hours()); // a Rejected line is listed but not counted (BPA-73 scenario 3)
            }
        }
        WorkPackHours hours = new WorkPackHours(lines, total);

        List<WorkPackWeek> progress = detail.weeks(header.id()).stream().map(w -> {
            List<WorkPackRetraction> retractions = detail.retractions(w.progressId()).stream().map(r -> {
                WorkPackRetraction retraction = new WorkPackRetraction(r.approvedPct(), r.reason(), r.requestedBy()).decisionReason(r.decisionReason()).decidedBy(r.decidedBy())
                        .decidedAt(r.decidedAt());
                if (r.decision() != null) {
                    retraction.setDecision(WorkPackRetraction.DecisionEnum.fromValue(r.decision()));
                }
                return retraction;
            }).toList();
            WorkPackWeek week = new WorkPackWeek(w.weekEnding(), WorkPackWeek.PeriodStatusEnum.fromValue(w.periodStatus()), retractions).reportedPct(w.reportedPct())
                    .approvedPct(w.approvedPct()).earnedManhours(w.earnedManhours());
            if (w.ruleResult() != null) {
                week.setRuleResult(WorkPackWeek.RuleResultEnum.fromValue(w.ruleResult()));
            }
            return week;
        }).toList();

        return new WorkPackDetail(summary, overview, workers, hours, progress);
    }

    public WorkPackHistoryPage history(Header header, int page, int size) {
        List<WorkPackHistoryItem> items = detail.history(header.id(), page, size).stream().map(h -> {
            WorkPackHistoryItem item = new WorkPackHistoryItem(WorkPackHistoryItem.KindEnum.fromValue(h.kind()), h.at()).username(h.username()).tableName(h.tableName())
                    .columnName(h.columnName()).oldValue(h.oldValue()).newValue(h.newValue());
            if ("Notice".equals(h.kind())) {
                item.setNotice(new WorkPackHistoryItemNotice(h.drawingRef(), h.revision(), h.recipientUsername(), WorkPackHistoryItemNotice.ChannelEnum.fromValue(h.channel())));
            }
            return item;
        }).toList();
        return new WorkPackHistoryPage(items, detail.historyTotal(header.id()));
    }
}
