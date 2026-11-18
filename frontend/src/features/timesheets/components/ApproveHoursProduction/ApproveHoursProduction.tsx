import type { TaskScreenProps } from '@/features/tasks/tasks.types';
import { ApproveHours } from '../ApproveHours';

/** BPA-56: the Production Supervisor's approval (UserTask_ApproveHoursProduction); the screen itself is the shared ApproveHours. */
export function ApproveHoursProduction(props: TaskScreenProps) {
  return <ApproveHours {...props} title="Approve hours (Production)" />;
}
