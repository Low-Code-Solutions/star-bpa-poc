import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { getTask } from '../services/taskService';

/** One task, by key. A task sent back by the save step has a new key, so it is a new query. */
export function useTask(api: Api, taskKey: string) {
  return useQuery({ queryKey: ['task', taskKey], queryFn: () => getTask(api, taskKey) });
}
