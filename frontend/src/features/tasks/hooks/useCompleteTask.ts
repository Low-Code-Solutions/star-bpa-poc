import { useQueryClient } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { completeTask } from '../services/taskService';

/** Completes a task; after a completion the task list is refetched (ADR 0017). */
export function useCompleteTask(api: Api) {
  const queryClient = useQueryClient();
  return async (taskKey: string, variables: Record<string, unknown>) => {
    const result = await completeTask(api, taskKey, variables);
    if (result.ok) {
      await queryClient.invalidateQueries({ queryKey: ['tasks'] });
    }
    return result;
  };
}
