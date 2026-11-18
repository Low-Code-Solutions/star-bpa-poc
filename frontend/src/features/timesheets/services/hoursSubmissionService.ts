import type { Api } from '@/services/apiClient';
import type { HoursSubmission, LineAmendment } from '@/types/api';
import type { SaveResult } from './timesheetService';

// The Timekeeper's API (BPA-53). React never calls Camunda; the backend checks every rule and answers with the story's message.

/** GET /api/v1/hours-submissions/{id}: the sent hours with their lines; null when the call failed. */
export async function getHoursSubmission(api: Api, submissionId: string): Promise<HoursSubmission | null> {
  const { data } = await api.GET('/api/v1/hours-submissions/{submissionId}', { params: { path: { submissionId } } });
  return data ?? null;
}

/** POST /api/v1/digital-timesheet-lines/{lineId}/amendments: the Timekeeper's amendment of a sent line's hours, with a reason. */
export async function amendLine(api: Api, lineId: string, body: LineAmendment): Promise<SaveResult> {
  const { error, response } = await api.POST('/api/v1/digital-timesheet-lines/{lineId}/amendments', {
    params: { path: { lineId } },
    body,
  });
  return response.ok
    ? { ok: true }
    : { ok: false, status: response.status, detail: (error as { detail?: string } | undefined)?.detail };
}
