import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { TaskScreenProps } from '@/features/tasks/tasks.types';
import { fieldMessages } from '../../fieldMessages';
import { useWorkPackForm } from '../../hooks/useWorkPackForm';
import { WorkPackFields } from '../WorkPackFields';

export { fieldMessages };

/**
 * BPA-6: the Create Work Pack task screen (UserTask_CreateWorkPack) on the shared TaskScreenShell, completed through the
 * backend task API (S14). Field spec: docs/form-specs/Form_CreateWorkPack.form; variable names: the bpa.tasks write
 * allow-list (BPA-26). After a rejected save the task comes back with what was entered (prefill).
 */
export function CreateWorkPack({ api, taskKey, onDone }: TaskScreenProps) {
  const state = useWorkPackForm(api);
  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Create Work Pack"
      onDone={onDone}
      onLoaded={state.prefill}
      check={state.check}
      variables={state.variables}
    >
      <WorkPackFields api={api} state={state} />
    </TaskScreenShell>
  );
}
