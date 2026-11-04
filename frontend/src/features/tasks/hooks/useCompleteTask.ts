import { useQueryClient } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { completeTask } from '../services/taskService';

/**
 * Completes a task; after a completion the task list is refetched (ADR 0017). The refetch is not awaited, so the screen
 * hands over to the task list straight after the POST, as before TanStack Query (BPA-47 review).
 */
export function useCompleteTask(api: Api) {
  const queryClient = useQueryClient();
  return async (taskKey: string, variables: Record<string, unknown>) => {
    const result = await completeTask(api, taskKey, variables);
    if (result.ok) {
      void queryClient.invalidateQueries({ queryKey: ['tasks'] });
    }
    return result;
  };
}
