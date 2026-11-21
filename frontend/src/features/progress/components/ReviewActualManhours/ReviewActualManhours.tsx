import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { showDate } from '@/utils/dates';
import { reviewMessages } from '../../reviewMessages';
import { getCbsActuals } from '../../services/progressService';

const two = (value?: number) => (value === undefined ? '' : value.toFixed(2));

/**
 * BPA-64: the Review actual man-hours screen on the shared TaskScreenShell (UserTask_ReviewActualManhours, group cost-controller). One row per CBS of the week: the
 * actual man-hours (approved hours, POC), the Current Forecast, the ETC (forecast minus actual; a negative ETC is highlighted and raises no extra alert), the variance %,
 * the result of the check and whether an alert was raised, and a remark per CBS, required where an alert was raised (FRD-22 §22.5.6.5). The lines with no CBS (D40) are
 * shown as one "No CBS" row with their hours and no remark. Complete task sends cbsReviews, one entry for every CBS row (bpa.tasks write allow-list). The checks use the
 * backend's texts, which it checks again. BPA-65: the read-only cost columns (cost, Revised Budget, cost variance %, cost result, all SAR) sit after the man-hour result and a cost alert needs a remark too.
 */
export function ReviewActualManhours({ api, taskKey, onDone }: TaskScreenProps) {
  const [weekEnding, setWeekEnding] = useState('');
  const [remarks, setRemarks] = useState<Record<string, string>>({});
  const week = useQuery({
    queryKey: ['cbs-actuals', weekEnding],
    queryFn: () => getCbsActuals(api, weekEnding),
    enabled: weekEnding !== '',
  });
  const rows = week.data?.cbs ?? [];
  const remarkOf = (cbs: string, stored?: string) => remarks[cbs] ?? stored ?? '';

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    setWeekEnding(typeof v.weekEnding === 'string' ? v.weekEnding : '');
  }

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Review actual man-hours"
      onDone={onDone}
      onLoaded={loaded}
      check={() =>
        week.isError || (week.isFetched && !week.data) ? ['The week could not be loaded'] : reviewMessages(rows, remarks)
      }
      variables={() => ({
        cbsReviews: rows.map((row) => ({ cbsCodeRef: row.cbsCodeRef, remark: remarkOf(row.cbsCodeRef, row.remark).trim() })),
      })}
    >
      {weekEnding !== '' && <p>{`Week ending ${showDate(weekEnding)}`}</p>}
      <table className="sign-table">
        <caption>Actual man-hours by CBS</caption>
        <thead>
          <tr>
            <th scope="col">CBS</th>
            <th scope="col">Actual MH</th>
            <th scope="col">Current Forecast MH</th>
            <th scope="col">ETC</th>
            <th scope="col">Variance %</th>
            <th scope="col">Result</th>
            <th scope="col">Cost SAR</th>
            <th scope="col">Revised Budget SAR</th>
            <th scope="col">Cost variance %</th>
            <th scope="col">Cost result</th>
            <th scope="col">Alert</th>
            <th scope="col">Remark</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.cbsCodeRef}>
              <td>{row.cbsCodeRef}</td>
              <td>{two(row.actualManhours)}</td>
              <td>{two(row.currentForecastManhours)}</td>
              <td className={row.etc !== undefined && row.etc < 0 ? 'sign-table__cell--negative' : undefined}>{two(row.etc)}</td>
              <td>{two(row.variancePct)}</td>
              <td>{row.result}</td>
              <td>{two(row.actualCost)}</td>
              <td>{two(row.revisedBudgetCost)}</td>
              <td>{two(row.costVariancePct)}</td>
              <td>{row.costResult}</td>
              <td>{row.alert || row.costAlert ? 'Alert raised' : ''}</td>
              <td>
                <input
                  type="text"
                  aria-label={`Remark for ${row.cbsCodeRef}`}
                  value={remarkOf(row.cbsCodeRef, row.remark)}
                  placeholder={row.alert || row.costAlert ? 'Required' : ''}
                  onChange={(e) => setRemarks((all) => ({ ...all, [row.cbsCodeRef]: e.target.value }))}
                />
              </td>
            </tr>
          ))}
          {week.data && (
            <tr>
              <td>No CBS</td>
              <td>{two(week.data.noCbsManhours)}</td>
              <td />
              <td />
              <td />
              <td />
              <td />
              <td />
              <td />
              <td />
              <td />
              <td />
            </tr>
          )}
        </tbody>
      </table>
    </TaskScreenShell>
  );
}
