import type { Api } from '@/services/apiClient';
import type { CbsActualsWeek, ProgressWeek, VarianceAlert } from '@/types/api';

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
