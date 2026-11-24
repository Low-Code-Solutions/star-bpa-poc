import type { Api } from '@/services/apiClient';
import { startWorkPack } from '../services/workpackService';

/** Starts a Work Pack request for a project (BPA-78); the caller waits for its Create Work Pack task (TaskList). */
export function useStartWorkPack(api: Api) {
  return (projectRef: string) => startWorkPack(api, projectRef);
}
