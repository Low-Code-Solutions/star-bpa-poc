import type { Api } from '@/services/apiClient';
import type { StartResult } from '../workpacks.types';

/** POST /api/v1/workpacks (BPA-6, D52, S20): starts a Work Pack request; no body (BPA-46 Q1). */
export async function startWorkPack(api: Api): Promise<StartResult> {
  const { data, response } = await api.POST('/api/v1/workpacks');
  if (!response.ok || !data) {
    return { status: response.status };
  }
  return data;
}
