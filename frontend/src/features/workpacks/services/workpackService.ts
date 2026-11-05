import type { Api } from '@/services/apiClient';
import type { LookAhead, WorkPackPrintSummary } from '@/types/api';
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

/** GET /api/v1/workpack-prints (BPA-10): the Work Packs that have a print, newest first; null when the call failed. */
export async function listWorkPackPrints(api: Api): Promise<WorkPackPrintSummary[] | null> {
  const { data } = await api.GET('/api/v1/workpack-prints');
  return data ?? null;
}

/** The outcome of fetching a print: the PDF as a blob, or the status and the contract's problem `detail`. */
export type PrintResult = { ok: true; blob: Blob } | { ok: false; status: number; detail?: string };

/**
 * GET /api/v1/workpacks/{workpackNumber}/print (BPA-10; 09 S4): the stored PDF as a blob, to show in the browser's viewer. A 404 says
 * "Workpack print not available" (not scheduled yet) or "Work Pack not found".
 */
export async function getWorkPackPrint(api: Api, workpackNumber: string): Promise<PrintResult> {
  const { data, error, response } = await api.GET('/api/v1/workpacks/{workpackNumber}/print', {
    params: { path: { workpackNumber } },
    parseAs: 'blob',
  });
  if (response.ok && data) {
    return { ok: true, blob: data };
  }
  const detail =
    typeof error === 'object' && error !== null && 'detail' in error ? String((error as { detail: unknown }).detail) : undefined;
  return { ok: false, status: response.status, detail };
}
