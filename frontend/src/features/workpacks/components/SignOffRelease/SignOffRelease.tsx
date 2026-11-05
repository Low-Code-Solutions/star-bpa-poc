import { useState } from 'react';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { showDate } from '@/utils/dates';
import { signOffMessages, type SignOffDecision } from '../../signOffMessages';

type Details = {
  activityRef: string;
  drawingRef: string;
  departmentRef: string;
  completionDate: string;
  phases: Array<{ phase: string; manhours: string }>;
  materials: Array<{ materialRef: string; phase: string }>;
};

const NO_DETAILS: Details = { activityRef: '', drawingRef: '', departmentRef: '', completionDate: '', phases: [], materials: [] };

/**
 * BPA-9: the Sign off release task screen (UserTask_SignOffRelease) on the shared TaskScreenShell. The Project Engineer sees the
 * Work Pack that passed the release check, read-only, and approves the release or declines it with a reason. The reason is
 * checked here, and again by the backend before the task completes (09 S14). The decision and the reason always start empty:
 * a Work Pack declined once and confirmed again must not arrive here with the old decline filled in.
 * Field spec: docs/form-specs/Form_SignOffRelease.form; variable names: the bpa.tasks write allow-list (BPA-26).
 */
export function SignOffRelease({ api, taskKey, onDone }: TaskScreenProps) {
  const [workpackNumber, setWorkpackNumber] = useState('');
  const [details, setDetails] = useState<Details>(NO_DETAILS);
  const [decision, setDecision] = useState<SignOffDecision>('');
  const [reason, setReason] = useState('');

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    const text = (value: unknown) => (typeof value === 'string' ? value : '');
    setWorkpackNumber(task.workpackNumber ?? '');
    setDetails({
      activityRef: text(v.activityRef),
      drawingRef: text(v.drawingRef),
      departmentRef: text(v.departmentRef),
      completionDate: text(v.completionDate),
      phases: Array.isArray(v.phases)
        ? v.phases.map((p: { phase?: unknown; manhours?: unknown }) => ({
            phase: text(p.phase),
            manhours: String(p.manhours ?? ''),
          }))
        : [],
      materials: Array.isArray(v.materials)
        ? v.materials.map((m: { materialRef?: unknown; phase?: unknown }) => ({
            materialRef: text(m.materialRef),
            phase: text(m.phase),
          }))
        : [],
    });
  }

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Sign off release"
      onDone={onDone}
      onLoaded={loaded}
      check={() => signOffMessages(decision, reason)}
      variables={() =>
        decision === 'Declined' ? { signOffDecision: 'Declined', declineReason: reason.trim() } : { signOffDecision: decision }
      }
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
        <table className="sign-table">
          <caption>Phases</caption>
          <thead>
            <tr>
              <th scope="col">Phase</th>
              <th scope="col">Man hours</th>
            </tr>
          </thead>
          <tbody>
            {details.phases.map((p, i) => (
              <tr key={`${p.phase}-${i}`}>
                <td>{p.phase}</td>
                <td>{p.manhours}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <table className="sign-table">
          <caption>Materials</caption>
          <thead>
            <tr>
              <th scope="col">Material</th>
              <th scope="col">Phase</th>
            </tr>
          </thead>
          <tbody>
            {details.materials.map((m, i) => (
              <tr key={`${m.materialRef}-${i}`}>
                <td>{m.materialRef}</td>
                <td>{m.phase}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <fieldset className="choices">
        <legend>Decision</legend>
        <label>
          <input
            type="radio"
            name="signOffDecision"
            value="Approved"
            checked={decision === 'Approved'}
            onChange={() => setDecision('Approved')}
          />
          Approve release
        </label>
        <label>
          <input
            type="radio"
            name="signOffDecision"
            value="Declined"
            checked={decision === 'Declined'}
            onChange={() => setDecision('Declined')}
          />
          Decline
        </label>
      </fieldset>
      {decision === 'Declined' && (
        <Field label="Reason for declining">
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
        </Field>
      )}
    </TaskScreenShell>
  );
}
