import type { Api } from '@/services/apiClient';
import type { WorkPackDetail } from '@/types/api';
import { showDate } from '@/utils/dates';
import { useWorkPackHistory } from '../../hooks/useWorkPackHistory';

const EMPTY = '–';
const two = (value?: number | null) => (value == null ? EMPTY : Number(value).toFixed(2));

/** The Overview tab (BPA-73): phases and man-hours, materials, the schedule and the print (shown as a date, no PDF button: the page stays read only). */
export function OverviewTab({ detail }: { detail: WorkPackDetail }) {
  const { overview } = detail;
  const total = overview.phases.reduce((sum, p) => sum + Number(p.manhours), 0);
  return (
    <>
      <table className="sign-table" aria-label="Phases and man-hours">
        <thead>
          <tr>
            <th>Phase</th>
            <th>Man-hours</th>
          </tr>
        </thead>
        <tbody>
          {overview.phases.map((p) => (
            <tr key={p.phase}>
              <td>{p.phase}</td>
              <td>{two(p.manhours)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th>Total</th>
            <th>{total.toFixed(2)}</th>
          </tr>
        </tfoot>
      </table>
      <h4>Materials</h4>
      {overview.materials.length === 0 ? (
        <p>No materials</p>
      ) : (
        <ul>
          {overview.materials.map((m) => (
            <li key={`${m.materialRef}-${m.phase}`}>{`${m.materialRef} (${m.phase})`}</li>
          ))}
        </ul>
      )}
      <h4>Schedule</h4>
      <p>
        {overview.schedule
          ? `Planned ${showDate(overview.schedule.plannedStartDate)} to ${showDate(overview.schedule.plannedEndDate)}, scheduled by ${overview.schedule.scheduledBy}`
          : 'Not scheduled yet'}
      </p>
      <h4>Workpack print</h4>
      <p>{overview.print ? `Printed ${showDate(overview.print.generatedAt.slice(0, 10))}` : 'Not printed yet'}</p>
    </>
  );
}

/** The Workers tab (BPA-73): the assigned Workers, with "Hours loaded; not removed" for a Worker kept with hours booked (BPA-13). */
export function WorkersTab({ detail }: { detail: WorkPackDetail }) {
  if (detail.workers.length === 0) {
    return <p>No Workers assigned yet</p>;
  }
  return (
    <table className="sign-table" aria-label="Workers">
      <thead>
        <tr>
          <th>Badge No</th>
          <th>Name</th>
          <th>Role</th>
          <th>Estimated hours</th>
          <th>Hour Journal</th>
          <th>Time sheet</th>
          <th>Note</th>
        </tr>
      </thead>
      <tbody>
        {detail.workers.map((w) => (
          <tr key={w.badgeNo}>
            <td>{w.badgeNo}</td>
            <td>{w.workerName}</td>
            <td>{w.role ?? EMPTY}</td>
            <td>{two(w.estimatedHours)}</td>
            <td>{w.journalStatus ?? EMPTY}</td>
            <td>{w.timesheetStatus ?? EMPTY}</td>
            <td>{w.hoursLoadedNotRemoved ? 'Hours loaded; not removed' : ''}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const STEPS: Record<string, string> = { Timekeeper: 'Timekeeper', ProductionSupervisor: 'Production Supervisor', Ppc: 'PPC' };

/** The Hours tab (BPA-73): every line with its status and, while its submission waits, the approval step; the total leaves the Rejected lines out. */
export function HoursTab({ detail }: { detail: WorkPackDetail }) {
  const { lines, totalHours } = detail.hours;
  if (lines.length === 0) {
    return <p>No hours booked yet</p>;
  }
  return (
    <>
      <table className="sign-table" aria-label="Hours">
        <thead>
          <tr>
            <th>Badge No</th>
            <th>Work date</th>
            <th>Phase</th>
            <th>Hours</th>
            <th>Status</th>
            <th>Waiting at</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={`${l.badgeNo}-${l.workDate}-${l.phase}`}>
              <td>{l.badgeNo}</td>
              <td>{showDate(l.workDate)}</td>
              <td>{l.phase}</td>
              <td>{two(l.hours)}</td>
              <td>{l.status}</td>
              <td>{l.step ? STEPS[l.step] : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        <strong>{`Total: ${two(totalHours)} h`}</strong> (Rejected lines are not counted)
      </p>
    </>
  );
}

/** The Progress tab (BPA-73): each week the Work Pack has a progress row: status, reported and approved %, the Earned man-hours of its activity, retractions. */
export function ProgressTab({ detail }: { detail: WorkPackDetail }) {
  if (detail.progress.length === 0) {
    return <p>No progress recorded yet</p>;
  }
  return (
    <table className="sign-table" aria-label="Progress">
      <thead>
        <tr>
          <th>Week ending</th>
          <th>Status</th>
          <th>Reported %</th>
          <th>Approved %</th>
          <th>Earned MH (activity)</th>
          <th>Retractions</th>
        </tr>
      </thead>
      <tbody>
        {detail.progress.map((w) => (
          <tr key={w.weekEnding}>
            <td>{showDate(w.weekEnding)}</td>
            <td>{w.periodStatus}</td>
            <td>{two(w.reportedPct)}</td>
            <td>{two(w.approvedPct)}</td>
            <td>{two(w.earnedManhours)}</td>
            <td>
              {w.retractions.length === 0 ? EMPTY : w.retractions.map((r) => `${r.reason} (${r.decision ?? 'open'})`).join('; ')}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The History tab (BPA-73): the audit log of the Work Pack and its records, and the drawing notices, newest first, a page at a time ("Show more" reads, it changes nothing). */
export function HistoryTab({ api, workpackNumber }: { api: Api; workpackNumber: string }) {
  const history = useWorkPackHistory(api, workpackNumber, true);
  if (history.isPending) {
    return <p>Loading the history</p>;
  }
  if (history.isError) {
    return <p role="alert">The history could not be loaded</p>;
  }
  const items = history.data.pages.flatMap((p) => p.items);
  if (items.length === 0) {
    return <p>No history yet</p>;
  }
  return (
    <>
      <table className="sign-table" aria-label="History">
        <thead>
          <tr>
            <th>When</th>
            <th>Who</th>
            <th>Record</th>
            <th>Column</th>
            <th>Change</th>
          </tr>
        </thead>
        <tbody>
          {items.map((h, i) => (
            <tr key={`${h.at}-${i}`}>
              <td>{showDate(h.at.slice(0, 10))}</td>
              {h.kind === 'Notice' ? (
                <>
                  <td>{h.notice?.recipientUsername}</td>
                  <td>Drawing notice</td>
                  <td>{h.notice?.channel}</td>
                  <td>{`${h.notice?.drawingRef} revision ${h.notice?.revision}`}</td>
                </>
              ) : (
                <>
                  <td>{h.username}</td>
                  <td>{h.tableName}</td>
                  <td>{h.columnName}</td>
                  <td>{`${h.oldValue ?? EMPTY} → ${h.newValue ?? EMPTY}`}</td>
                </>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      {history.hasNextPage ? (
        <button type="button" onClick={() => history.fetchNextPage()} disabled={history.isFetchingNextPage}>
          Show more
        </button>
      ) : null}
    </>
  );
}
