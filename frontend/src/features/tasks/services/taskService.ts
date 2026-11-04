import type { Api } from '@/services/apiClient';
import type { CompleteResult, Task, TaskSummary } from '../tasks.types';

// The backend task API (09 S14, ADR 0004; BPA-26). React never calls Camunda.

/** GET /api/v1/tasks: the session user's open tasks; anything but a list reads as none. */
export async function listTasks(api: Api): Promise<TaskSummary[]> {
  const { data } = await api.GET('/api/v1/tasks');
  return Array.isArray(data) ? data : [];
}

/** GET /api/v1/tasks/{key}: the task with its read-allow-listed variables and validationMessages. */
export async function getTask(api: Api, taskKey: string): Promise<Task | null> {
  const { data } = await api.GET('/api/v1/tasks/{taskKey}', { params: { path: { taskKey } } });
  return data ?? null;
}

/** POST /api/v1/tasks/{key}/complete. The backend checks group, allow-list and state (403, 400, 409) and adds completedBy. */
export async function completeTask(api: Api, taskKey: string, variables: Record<string, unknown>): Promise<CompleteResult> {
  const { error, response } = await api.POST('/api/v1/tasks/{taskKey}/complete', {
    params: { path: { taskKey } },
    body: { variables },
  });
  if (response.ok) {
    return { ok: true };
  }
  return { ok: false, status: response.status, detail: (error as { detail?: string } | undefined)?.detail };
}
