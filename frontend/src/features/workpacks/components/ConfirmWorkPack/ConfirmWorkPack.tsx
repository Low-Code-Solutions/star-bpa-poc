import { useState } from 'react';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { useWorkPackForm } from '../../hooks/useWorkPackForm';
import { WorkPackFields } from '../WorkPackFields';

/**
 * BPA-23: the Confirm Workpack task screen (UserTask_ConfirmWorkPack) on the shared TaskScreenShell. The Draft Work Pack is
 * editable with the same fields and field checks as at creation (D21, S14); the save step re-checks them and owns the
 * cross-field rules (D16, D74, D56). The saved material lines come with their ids, which go back unchanged so the correction
 * updates them in place (data model A4, F13). Shows the Workpack number read-only and the latest return reason (D51).
 * Field spec: docs/form-specs/Form_ConfirmWorkPack.form; variable names: the bpa.tasks write allow-list (BPA-26).
 */
export function ConfirmWorkPack({ api, taskKey, onDone }: TaskScreenProps) {
  const state = useWorkPackForm(api);
  const [workpackNumber, setWorkpackNumber] = useState('');
  const [returnReason, setReturnReason] = useState<string | null>(null);

  function loaded(task: Task) {
    setWorkpackNumber(task.workpackNumber ?? '');
    setReturnReason(task.confirmMessage ?? null);
    state.prefill(task);
  }

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Confirm Workpack"
      onDone={onDone}
      onLoaded={loaded}
      check={state.check}
      variables={state.variables}
    >
      <label className="field field--number">
        Workpack number
        <input value={workpackNumber} readOnly />
      </label>
      {returnReason && (
        <section aria-label="Return reason" className="notice notice--return">
          <p>
            <span className="notice__label">Returned:</span> <span>{returnReason}</span>
          </p>
        </section>
      )}
      <WorkPackFields api={api} state={state} />
    </TaskScreenShell>
  );
}
