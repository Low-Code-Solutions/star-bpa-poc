import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { showDate } from '@/utils/dates';
import { retractionDecisionMessages, type RetractionChoice } from '../../retractionDecisionMessages';
import { getProgressWeek } from '../../services/progressService';

const two = (value?: number) => (value === undefined ? '' : value.toFixed(2));
const EMPTY: RetractionChoice = { decision: '', reason: '' };

/**
 * BPA-63: the Approve progress retraction screen on the shared TaskScreenShell (UserTask_ApproveRetraction, group project-manager). One task lists every Work
 * Pack that waits for a retraction: the last locked %, the new % and PPC's reason (the rows of GET /api/v1/progress-periods/{weekEnding}/workpacks that have an
 * open request, plan Q9). The Project Manager decides each one, Approved or Declined; a decline needs a reason (D8, at most 250 characters). The variable is
 * retractionDecisions, one entry for every row (bpa.tasks write allow-list). The checks use the backend's texts, which it checks again; a 409 "You cannot
 * approve a retraction you requested" is shown as it is.
 */
export function ApproveRetraction({ api, taskKey, onDone }: TaskScreenProps) {
  const [weekEnding, setWeekEnding] = useState('');
  const [choices, setChoices] = useState<Record<string, RetractionChoice>>({});
  const week = useQuery({
    queryKey: ['progress-week', weekEnding],
    queryFn: () => getProgressWeek(api, weekEnding),
    enabled: weekEnding !== '',
  });
  const rows = (week.data?.workpacks ?? []).filter((row) => row.retractionReason !== undefined);
  const choiceOf = (workpack: string): RetractionChoice => choices[workpack] ?? EMPTY;

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    setWeekEnding(typeof v.weekEnding === 'string' ? v.weekEnding : '');
  }

  const change = (workpack: string, choice: Partial<RetractionChoice>) =>
    setChoices((all) => ({ ...all, [workpack]: { ...(all[workpack] ?? EMPTY), ...choice } }));

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Approve progress retraction"
      onDone={onDone}
      onLoaded={loaded}
      check={() =>
        week.isError || (week.isFetched && rows.length === 0)
          ? ['No retraction is waiting for a decision']
          : retractionDecisionMessages(rows.map((row) => choiceOf(row.workpackNumber)))
      }
      variables={() => ({
        retractionDecisions: rows.map((row) => {
          const choice = choiceOf(row.workpackNumber);
          return choice.decision === 'Declined'
            ? { workpackNumber: row.workpackNumber, retractionDecision: 'Declined', reason: choice.reason.trim() }
            : { workpackNumber: row.workpackNumber, retractionDecision: choice.decision };
        }),
      })}
    >
      {weekEnding !== '' && <p>{`Week ending ${showDate(weekEnding)}`}</p>}
      <table className="sign-table">
        <caption>Retractions waiting for a decision</caption>
        <thead>
          <tr>
            <th scope="col">Work Pack</th>
            <th scope="col">Last locked %</th>
            <th scope="col">New %</th>
            <th scope="col">PPC reason</th>
            <th scope="col">Decision</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const choice = choiceOf(row.workpackNumber);
            return (
              <tr key={row.workpackNumber}>
                <td>{row.workpackNumber}</td>
                <td>{two(row.lastLockedPct)}</td>
                <td>{two(row.approvedPct)}</td>
                <td>{row.retractionReason}</td>
                <td>
                  <fieldset className="choices">
                    <legend>{`Decision for ${row.workpackNumber}`}</legend>
                    {(['Approved', 'Declined'] as const).map((value) => (
                      <label key={value}>
                        <input
                          type="radio"
                          name={`decision-${row.workpackNumber}`}
                          aria-label={`${value} for ${row.workpackNumber}`}
                          checked={choice.decision === value}
                          onChange={() => change(row.workpackNumber, { decision: value })}
                        />
                        {value}
                      </label>
                    ))}
                    {choice.decision === 'Declined' && (
                      <Field label={`Reason for ${row.workpackNumber}`}>
                        <textarea
                          rows={3}
                          value={choice.reason}
                          onChange={(e) => change(row.workpackNumber, { reason: e.target.value })}
                        />
                      </Field>
                    )}
                  </fieldset>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </TaskScreenShell>
  );
}
