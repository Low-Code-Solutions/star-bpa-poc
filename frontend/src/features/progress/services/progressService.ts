import type { Api } from '@/services/apiClient';
import type { CbsActualsWeek, EarnedManhoursWeek, ProgressWeek, VarianceAlert } from '@/types/api';

// The week's Work Packs for the completion % approval (BPA-60; E2-10). React never calls Camunda; the backend works out every figure.

/** GET /api/v1/progress-periods/{weekEnding}/workpacks: one row per Work Pack of the week; null when the call failed (404 "Week not found", 403). */
export async function getProgressWeek(api: Api, weekEnding: string): Promise<ProgressWeek | null> {
  try {
    const { data } = await api.GET('/api/v1/progress-periods/{weekEnding}/workpacks', { params: { path: { weekEnding } } });
    return data ?? null;
  } catch {
    return null;
  }
}

/** GET /api/v1/progress-periods/{weekEnding}/cbs-actuals (BPA-64): each CBS's actual man-hours against the Current Forecast; null when the call failed (404 "Week not found", 403). */
export async function getCbsActuals(api: Api, weekEnding: string): Promise<CbsActualsWeek | null> {
  try {
    const { data } = await api.GET('/api/v1/progress-periods/{weekEnding}/cbs-actuals', { params: { path: { weekEnding } } });
    return data ?? null;
  } catch {
    return null;
  }
}

/** GET /api/v1/me/variance-alerts (BPA-64): the session user's in-system variance alerts, newest first; null when the call failed. */
export async function listMyVarianceAlerts(api: Api): Promise<VarianceAlert[] | null> {
  try {
    const { data } = await api.GET('/api/v1/me/variance-alerts');
    return data ?? null;
  } catch {
    return null;
  }
}

/** GET /api/v1/progress-periods/{weekEnding}/earned-manhours (BPA-62; the Project Manager reads it for BPA-66): each activity, its % and Earned man-hours; null when the call failed. */
export async function getEarnedManhours(api: Api, weekEnding: string): Promise<EarnedManhoursWeek | null> {
  try {
    const { data } = await api.GET('/api/v1/progress-periods/{weekEnding}/earned-manhours', { params: { path: { weekEnding } } });
    return data ?? null;
  } catch {
    return null;
  }
}

/** The answer of a call that changes something: ok, or the status and the backend's problem text (BPA-83). */
export type ReopenResult = { ok: true } | { ok: false; status: number; detail?: string };

/**
 * POST /api/v1/progress-periods/{weekEnding}/reopen (BPA-83): the Project Manager reopens a Locked week with a reason. The backend checks the setting, the group, the reason and the
 * status and answers with the story's texts ("Enter a reason to reopen the week", "Reopening a locked week is not allowed"); the screen shows them as they come.
 */
export async function reopenWeek(api: Api, weekEnding: string, reason: string): Promise<ReopenResult> {
  try {
    const { response, error } = await api.POST('/api/v1/progress-periods/{weekEnding}/reopen', {
      params: { path: { weekEnding } },
      body: { reason },
    });
    return response.ok
      ? { ok: true }
      : { ok: false, status: response.status, detail: (error as { detail?: string } | undefined)?.detail };
  } catch {
    return { ok: false, status: 0 };
  }
}
