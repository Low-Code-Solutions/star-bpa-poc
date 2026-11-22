import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { showDate } from '@/utils/dates';
import { approvalMessages, entryOf, needsReason, referencePct, type Edit } from '../../approvalMessages';
import { getProgressWeek } from '../../services/progressService';

const two = (value?: number) => (value === undefined ? '' : value.toFixed(2));

/**
 * BPA-60: the Approve completion % screen on the shared TaskScreenShell (UserTask_ApproveCompletion, group ppc-team). One row per Work Pack of the week: the
 * reported %, the last locked %, the hours-based % and "Reported by production" (information only, C-E2-10), the FMS actual hours beside the POC approved hours
 * with the difference (information only, BPA-59 (a)), then the approved % (pre-filled by the backend by progress.basis) and a remark, required when the approved
 * % differs from the reported % (or from the last locked % when there is "No report row", D6). Complete task approves the whole week (decision (d)): the variable
 * is completionApprovals, one entry for every row (bpa.tasks write allow-list). The checks use the backend's texts, which it checks again; a 409 "Week ending
 * <date> must be locked first" is shown as it is. BPA-63: where an approved % is below the last locked %, a retraction reason is asked for (the backend then makes the request
 * for the Project Manager), and the Project Manager decline is shown on the row ("Retraction declined: <reason>", plan Q5).
 */
export function ApproveCompletion({ api, taskKey, onDone }: TaskScreenProps) {
  const [weekEnding, setWeekEnding] = useState('');
  const [notice, setNotice] = useState('');
  const [edits, setEdits] = useState<Record<string, Edit>>({});
  const week = useQuery({
    queryKey: ['progress-week', weekEnding],
    queryFn: () => getProgressWeek(api, weekEnding),
    enabled: weekEnding !== '',
  });
  const rows = week.data?.workpacks ?? [];

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    setWeekEnding(typeof v.weekEnding === 'string' ? v.weekEnding : '');
    // BPA-66: why the Project Manager sent the week back to this screen (read allow-list weekDecision, weekChangeTarget, weekDecisionReason)
    const reason = typeof v.weekDecisionReason === 'string' ? v.weekDecisionReason : '';
    setNotice(
      v.weekDecision === 'ChangesRequested' && v.weekChangeTarget === 'Progress' && reason !== ''
        ? `Week sent back: ${reason}`
        : '',
    );
  }

  const change = (workpack: string, edit: Edit) => setEdits((all) => ({ ...all, [workpack]: { ...all[workpack], ...edit } }));

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Approve completion %"
      onDone={onDone}
      onLoaded={loaded}
      check={() =>
        week.isError || (week.isFetched && !week.data) ? ['The week could not be loaded'] : approvalMessages(rows, edits)
      }
      variables={() => ({
        completionApprovals: rows.map((row) => {
          const entry = entryOf(row, edits[row.workpackNumber]);
          const remark = entry.remark.trim();
          const reason = needsReason(row, entry.pctText) ? entry.reason.trim() : '';
          return {
            workpackNumber: row.workpackNumber,
            approvedPct: Number(entry.pctText),
            ...(remark === '' ? {} : { remark }),
            ...(reason === '' ? {} : { retractionReason: reason }),
          };
        }),
      })}
    >
      {weekEnding !== '' && <p>{`Week ending ${showDate(weekEnding)}`}</p>}
      {notice !== '' && <p className="notice notice--return">{notice}</p>}
      <table className="sign-table">
        <caption>Completion % of the week</caption>
        <thead>
          <tr>
            <th scope="col">Work Pack</th>
            <th scope="col">Reported %</th>
            <th scope="col">Last locked %</th>
            <th scope="col">Hours-based %</th>
            <th scope="col">Reported by production</th>
            <th scope="col">FMS actual hours</th>
            <th scope="col">POC approved hours</th>
            <th scope="col">Difference</th>
            <th scope="col">Approved %</th>
            <th scope="col">Remark</th>
            <th scope="col">Retraction</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const entry = entryOf(row, edits[row.workpackNumber]);
            return (
              <tr key={row.workpackNumber}>
                <td>{row.workpackNumber}</td>
                <td>{row.noReportRow ? 'No report row' : two(row.reportedPct)}</td>
                <td>{two(row.lastLockedPct)}</td>
                <td>{two(row.hoursBasedPct)}</td>
                <td>
                  {row.reportedByProductionPct === undefined
                    ? ''
                    : `Reported by production: ${two(row.reportedByProductionPct)}%`}
                </td>
                <td>{two(row.fmsActualHours)}</td>
                <td>{two(row.approvedHours)}</td>
                <td>{two(row.hoursDifference)}</td>
                <td>
                  <input
                    type="text"
                    inputMode="decimal"
                    aria-label={`Approved % for ${row.workpackNumber}`}
                    value={entry.pctText}
                    onChange={(e) => change(row.workpackNumber, { pct: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    type="text"
                    aria-label={`Remark for ${row.workpackNumber}`}
                    value={entry.remark}
                    placeholder={entry.pctText.trim() !== '' && Number(entry.pctText) !== referencePct(row) ? 'Required' : ''}
                    onChange={(e) => change(row.workpackNumber, { remark: e.target.value })}
                  />
                </td>
                <td>
                  {needsReason(row, entry.pctText) && (
                    <input
                      type="text"
                      aria-label={`Retraction reason for ${row.workpackNumber}`}
                      value={entry.reason}
                      placeholder="Required"
                      onChange={(e) => change(row.workpackNumber, { reason: e.target.value })}
                    />
                  )}
                  {row.retractionDeclinedReason !== undefined && (
                    <span>{`Retraction declined: ${row.retractionDeclinedReason}`}</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </TaskScreenShell>
  );
}
