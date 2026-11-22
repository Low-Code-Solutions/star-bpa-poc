import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { showDate } from '@/utils/dates';
import { weekDecisionMessages, type WeekChoice } from '../../weekDecisionMessages';
import { getCbsActuals, getEarnedManhours, getProgressWeek } from '../../services/progressService';

const two = (value?: number) => (value === undefined ? '' : value.toFixed(2));
const EMPTY: WeekChoice = { decision: '', target: 'Progress', reason: '' };

/**
 * BPA-66: the Approve weekly progress screen on the shared TaskScreenShell (UserTask_ApproveWeeklyProgress, group project-manager). Read-only: per Work Pack the approved % and
 * last week's % (GET /api/v1/progress-periods/{weekEnding}/workpacks), and for a Work Pack approved at 100% its unsent (Draft, Returned) lines, information only (BPA-67 rule (c));
 * per activity the Earned man-hours (earned-manhours, BPA-62); the week's man-hour and cost alerts with the Cost Controller's remarks (cbs-actuals, BPA-64 and BPA-65). The Project
 * Manager approves the week or requests changes, sending it back to Progress (PPC) or to the Cost review with a reason. The variables are weekDecision, and for a send-back
 * weekChangeTarget and weekDecisionReason (bpa.tasks write allow-list). The checks use the backend's texts, which it checks again; a 409 "You cannot approve a week whose
 * progress you approved" is shown as it is. The widget texts are ASSUMPTIONs (plan section 6).
 */
export function ApproveWeeklyProgress({ api, taskKey, onDone }: TaskScreenProps) {
  const [weekEnding, setWeekEnding] = useState('');
  const [choice, setChoice] = useState<WeekChoice>(EMPTY);
  const enabled = weekEnding !== '';
  const week = useQuery({ queryKey: ['progress-week', weekEnding], queryFn: () => getProgressWeek(api, weekEnding), enabled });
  const earned = useQuery({
    queryKey: ['earned-manhours', weekEnding],
    queryFn: () => getEarnedManhours(api, weekEnding),
    enabled,
  });
  const cbs = useQuery({ queryKey: ['cbs-actuals', weekEnding], queryFn: () => getCbsActuals(api, weekEnding), enabled });
  const workpacks = week.data?.workpacks ?? [];
  const activities = earned.data?.activities ?? [];
  const alerted = (cbs.data?.cbs ?? []).filter((row) => row.alert || row.costAlert || (row.remark ?? '') !== '');

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    setWeekEnding(typeof v.weekEnding === 'string' ? v.weekEnding : '');
  }

  const change = (next: Partial<WeekChoice>) => setChoice((all) => ({ ...all, ...next }));

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Approve weekly progress"
      onDone={onDone}
      onLoaded={loaded}
      check={() =>
        week.isError || (week.isFetched && !week.data) ? ['The week could not be loaded'] : weekDecisionMessages(choice)
      }
      variables={() =>
        choice.decision === 'ChangesRequested'
          ? { weekDecision: 'ChangesRequested', weekChangeTarget: choice.target, weekDecisionReason: choice.reason.trim() }
          : { weekDecision: 'Approved' }
      }
    >
      {weekEnding !== '' && <p>{`Week ending ${showDate(weekEnding)}`}</p>}
      <table className="sign-table">
        <caption>Completion % of the week</caption>
        <thead>
          <tr>
            <th scope="col">Work Pack</th>
            <th scope="col">Approved %</th>
            <th scope="col">Last week's %</th>
          </tr>
        </thead>
        <tbody>
          {workpacks.map((row) => (
            <tr key={row.workpackNumber}>
              <td>{row.workpackNumber}</td>
              <td>{two(row.approvedPct)}</td>
              <td>{two(row.lastLockedPct)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {workpacks
        .filter((row) => (row.unsentLines ?? []).length > 0)
        .map((row) => (
          <table className="sign-table" key={`unsent-${row.workpackNumber}`}>
            <caption>{`Unsent hours of ${row.workpackNumber} (approved at 100%)`}</caption>
            <thead>
              <tr>
                <th scope="col">Worker</th>
                <th scope="col">Date</th>
                <th scope="col">Phase</th>
                <th scope="col">Hours</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {(row.unsentLines ?? []).map((line) => (
                <tr key={`${line.employeeRef}-${line.workDate}-${line.phase}-${line.status}`}>
                  <td>{line.employeeRef}</td>
                  <td>{showDate(line.workDate)}</td>
                  <td>{line.phase}</td>
                  <td>{two(line.hours)}</td>
                  <td>{line.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
      <table className="sign-table">
        <caption>Earned man-hours</caption>
        <thead>
          <tr>
            <th scope="col">Activity</th>
            <th scope="col">Name</th>
            <th scope="col">Actual %</th>
            <th scope="col">Budgeted units</th>
            <th scope="col">Earned MH</th>
            <th scope="col">Last locked Earned MH</th>
            <th scope="col">Week increase</th>
          </tr>
        </thead>
        <tbody>
          {activities.map((row) => (
            <tr key={row.activityRef}>
              <td>{row.activityRef}</td>
              <td>{row.activityName}</td>
              <td>{two(row.actualPct)}</td>
              <td>{two(row.budgetedUnits)}</td>
              <td>{two(row.earnedManhours)}</td>
              <td>{two(row.lastLockedEarnedManhours)}</td>
              <td>{two(row.weekIncrease)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <table className="sign-table">
        <caption>Variance alerts and remarks</caption>
        <thead>
          <tr>
            <th scope="col">CBS</th>
            <th scope="col">Man-hour variance %</th>
            <th scope="col">Cost variance %</th>
            <th scope="col">Alerts</th>
            <th scope="col">Cost Controller's remark</th>
          </tr>
        </thead>
        <tbody>
          {alerted.map((row) => (
            <tr key={row.cbsCodeRef}>
              <td>{row.cbsCodeRef}</td>
              <td>{two(row.variancePct)}</td>
              <td>{two(row.costVariancePct)}</td>
              <td>
                {row.alert && <span>Man-hour alert</span>} {row.costAlert && <span>Cost alert</span>}
              </td>
              <td>{row.remark}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <fieldset className="choices">
        <legend>Decision</legend>
        <label>
          <input
            type="radio"
            name="week-decision"
            checked={choice.decision === 'Approved'}
            onChange={() => change({ decision: 'Approved' })}
          />
          Approve week
        </label>
        <label>
          <input
            type="radio"
            name="week-decision"
            checked={choice.decision === 'ChangesRequested'}
            onChange={() => change({ decision: 'ChangesRequested' })}
          />
          Request changes
        </label>
        {choice.decision === 'ChangesRequested' && (
          <>
            <Field label="Send back to">
              <select value={choice.target} onChange={(e) => change({ target: e.target.value as WeekChoice['target'] })}>
                <option value="Progress">Progress (PPC)</option>
                <option value="CostReview">Cost review (Cost Controller)</option>
              </select>
            </Field>
            <Field label="Reason">
              <textarea rows={3} value={choice.reason} onChange={(e) => change({ reason: e.target.value })} />
            </Field>
          </>
        )}
      </fieldset>
    </TaskScreenShell>
  );
}
