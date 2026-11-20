import type { Api } from '@/services/apiClient';
import type { ProgressWeek } from '@/types/api';

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
