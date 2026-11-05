import type { Api } from '@/services/apiClient';
import type { LookAhead } from '@/types/api';
import type { StartResult } from '../workpacks.types';

/** POST /api/v1/workpacks (BPA-6, D52, S20): starts a Work Pack request; no body (BPA-46 Q1). */
export async function startWorkPack(api: Api): Promise<StartResult> {
  const { data, response } = await api.POST('/api/v1/workpacks');
  if (!response.ok || !data) {
    return { status: response.status };
  }
  return data;
}

/**
 * GET /api/v1/workpacks/{workpackNumber}/look-ahead (BPA-11, D48, 09 S20): the Look-Ahead PPC Plan-Dates of the Work Pack's
 * drawing. A drawing with no row comes back as 200 with no dates; a failed call gives null, and the screen shows nothing.
 */
export async function getLookAhead(api: Api, workpackNumber: string): Promise<LookAhead | null> {
  const { data } = await api.GET('/api/v1/workpacks/{workpackNumber}/look-ahead', { params: { path: { workpackNumber } } });
  return data ?? null;
}
