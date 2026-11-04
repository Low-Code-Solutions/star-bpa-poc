import { useAuth } from '@/context/AuthContext';
import { TaskList } from '@/features/tasks';
import type { Api } from '@/services/apiClient';

/** The portal's start page: the session user's tasks. */
export function TasksPage({ api }: { api: Api }) {
  const { me } = useAuth();
  return <TaskList api={api} groups={me?.groups ?? []} />;
}
