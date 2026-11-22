import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { TaskScreenProps } from '@/features/tasks/tasks.types';

/**
 * BPA-66: the Approve weekly progress screen (UserTask_ApproveWeeklyProgress, group project-manager). COMPILE STUB for the red run: the real screen comes in the green
 * commit.
 */
export function ApproveWeeklyProgress({ api, taskKey, onDone }: TaskScreenProps) {
  return (
    <TaskScreenShell api={api} taskKey={taskKey} title="Approve weekly progress" onDone={onDone} check={() => []} variables={() => ({})}>
      <p>Not built yet</p>
    </TaskScreenShell>
  );
}
