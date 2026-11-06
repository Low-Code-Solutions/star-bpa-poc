import { useAuth } from '@/context/AuthContext';
import { AssignmentFileUpload } from '@/features/assignment-files';
import { TaskList } from '@/features/tasks';
import { WorkPackPrints } from '@/features/workpacks';
import type { Api } from '@/services/apiClient';

/**
 * The portal's start page: the session user's tasks, and for the groups that may use them the list of Workpack prints (BPA-10) and the
 * Worker assignment file upload (BPA-12).
 */
export function TasksPage({ api }: { api: Api }) {
  const { me } = useAuth();
  const groups = me?.groups ?? [];
  return (
    <>
      <TaskList api={api} groups={groups} />
      <WorkPackPrints api={api} groups={groups} />
      <AssignmentFileUpload api={api} groups={groups} />
    </>
  );
}
