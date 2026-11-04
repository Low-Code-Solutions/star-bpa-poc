import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listTasks } from '../services/taskService';

/** The task list query (['tasks']); a completion refetches it (useCompleteTask). */
export function useTasks(api: Api) {
  return useQuery({ queryKey: ['tasks'], queryFn: () => listTasks(api) });
}
