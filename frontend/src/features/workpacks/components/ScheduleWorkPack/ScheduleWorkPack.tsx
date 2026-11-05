import { useState } from 'react';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { showDate } from '@/utils/dates';
import { useLookAhead } from '../../hooks/useLookAhead';
import { scheduleMessages } from '../../scheduleMessages';

type Details = { activityRef: string; drawingRef: string; departmentRef: string; completionDate: string };

const NO_DETAILS: Details = { activityRef: '', drawingRef: '', departmentRef: '', completionDate: '' };

/**
 * BPA-11: the Schedule Work Pack task screen (UserTask_ScheduleWorkPack) on the shared TaskScreenShell. The Production Planner sees
 * the Work Pack, its date to complete and the three Look-Ahead PPC Plan-Dates for its drawing, all read-only (no source says which
 * Plan-Date drives the start, so the Planner decides), and enters the planned start and end. The lead-time and order rules are the
 * save step's: the task comes back showing them. An end after the date to complete only shows a notice (D26). A scheduled Work Pack
 * is not scheduled again (D27). Field spec: docs/form-specs/Form_ScheduleWorkPack.form, with the data model's variable names
 * plannedStartDate and plannedEndDate (BPA-46 Q2; the bpa.tasks write allow-list).
 */
export function ScheduleWorkPack({ api, taskKey, onDone }: TaskScreenProps) {
  const [workpackNumber, setWorkpackNumber] = useState('');
  const [details, setDetails] = useState<Details>(NO_DETAILS);
  const [plannedStart, setPlannedStart] = useState('');
  const [plannedEnd, setPlannedEnd] = useState('');
  const lookAhead = useLookAhead(api, workpackNumber);

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    const text = (value: unknown) => (typeof value === 'string' ? value : '');
    setWorkpackNumber(task.workpackNumber ?? '');
    setDetails({
      activityRef: text(v.activityRef),
      drawingRef: text(v.drawingRef),
      departmentRef: text(v.departmentRef),
      completionDate: text(v.completionDate),
    });
    // A task sent back by the save step keeps what was entered.
    setPlannedStart(text(v.plannedStartDate));
    setPlannedEnd(text(v.plannedEndDate));
  }

  const dates: Array<[string, string | undefined]> = lookAhead
    ? [
        ['Shop Drg Mark', lookAhead.shopDrgMarkPlanDate],
        ['Assembly / Integration', lookAhead.assemblyPlanDate],
        ['Weld Map Drawing', lookAhead.weldMapPlanDate],
      ]
    : [];
  const hasDates = dates.some(([, date]) => date);
  // ISO dates compare as text. Equal dates give no notice (D26).
  const lateEnd = plannedEnd !== '' && details.completionDate !== '' && plannedEnd > details.completionDate;

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Schedule Work Pack"
      onDone={onDone}
      onLoaded={loaded}
      check={() => scheduleMessages(plannedStart, plannedEnd)}
      variables={() => ({ plannedStartDate: plannedStart, plannedEndDate: plannedEnd })}
    >
      <label className="field field--number">
        Workpack number
        <input value={workpackNumber} readOnly />
      </label>
      <section aria-label="Work Pack details" className="context-panel">
        <dl>
          <dt>Activity</dt>
          <dd>{details.activityRef}</dd>
          <dt>Drawing</dt>
          <dd>{details.drawingRef}</dd>
          <dt>Department</dt>
          <dd>{details.departmentRef}</dd>
          <dt>Date to complete</dt>
          <dd>{showDate(details.completionDate)}</dd>
        </dl>
      </section>
      {lookAhead && hasDates && (
        <section aria-label="Look-Ahead PPC plan dates" className="context-panel">
          <dl>
            {dates.map(([label, date]) => (
              <div key={label} className="context-panel__pair">
                <dt>{label}</dt>
                <dd>{date ? showDate(date) : 'Not set'}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
      {lookAhead && !hasDates && <p className="notice">{`No Look-Ahead plan dates for ${lookAhead.drawingRef}`}</p>}
      <Field label="Planned start">
        <input type="date" value={plannedStart} onChange={(e) => setPlannedStart(e.target.value)} />
      </Field>
      <Field label="Planned end">
        <input type="date" value={plannedEnd} onChange={(e) => setPlannedEnd(e.target.value)} />
      </Field>
      {lateEnd && (
        <p
          role="status"
          className="notice"
        >{`Planned end is after the date to complete (${showDate(details.completionDate)})`}</p>
      )}
    </TaskScreenShell>
  );
}
