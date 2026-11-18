import type { TaskScreenProps } from '@/features/tasks/tasks.types';
import { ApproveHours } from '../ApproveHours';

/** BPA-57: the PPC Team's final approval (UserTask_ApproveHoursPpc); the screen itself is the shared ApproveHours. */
export function ApproveHoursPpc(props: TaskScreenProps) {
  return <ApproveHours {...props} title="Approve hours (PPC)" />;
}
