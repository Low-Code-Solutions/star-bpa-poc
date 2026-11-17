import type { ComponentType } from 'react';
import type { Api } from '@/services/apiClient';
import type { Task, TaskSummary } from '@/types/api';

export type { Task, TaskSummary };

/**
 * What a task screen gets from the task list: the API client, the task key, what to do after a completion, and (BPA-50) how to go back to
 * the list without completing, for a screen that does not complete its task.
 */
export type TaskScreenProps = { api: Api; taskKey: string; onDone: () => void; onClose?: () => void };

export type TaskScreen = ComponentType<TaskScreenProps>;

/** The outcome of POST /api/v1/tasks/{key}/complete: ok, or the status and the contract's problem `detail`. */
export type CompleteResult = { ok: true } | { ok: false; status: number; detail?: string };
