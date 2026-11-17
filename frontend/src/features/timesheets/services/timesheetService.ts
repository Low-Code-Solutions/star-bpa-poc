import type { Api } from '@/services/apiClient';
import type { DigitalTimesheet, DigitalTimesheetLineChange, NewDigitalTimesheetLine, TimesheetLines } from '@/types/api';

// The hour lines API (BPA-50; E2-03). React never calls Camunda; the backend checks every rule and answers with the story's message.

/** The outcome of a save: ok, or the status and the contract's problem `detail`. */
export type SaveResult = { ok: true } | { ok: false; status: number; detail?: string };

const failed = (status: number, error: unknown): SaveResult => ({
  ok: false,
  status,
  detail: (error as { detail?: string } | undefined)?.detail,
});

/** GET /api/v1/digital-timesheets?workpackNumber= (BPA-15): the Work Pack's time sheets, with the ids the lines are keyed by. */
export async function listTimesheets(api: Api, workpackNumber: string): Promise<DigitalTimesheet[]> {
  const { data } = await api.GET('/api/v1/digital-timesheets', { params: { query: { workpackNumber } } });
  return Array.isArray(data) ? data : [];
}

/** GET /api/v1/digital-timesheets/{id}/lines: the Worker's lines, the Work Pack's phases and the open week; null when the call failed. */
export async function getTimesheetLines(api: Api, timesheetId: string): Promise<TimesheetLines | null> {
  const { data } = await api.GET('/api/v1/digital-timesheets/{timesheetId}/lines', { params: { path: { timesheetId } } });
  return data ?? null;
}

export async function addLine(api: Api, timesheetId: string, body: NewDigitalTimesheetLine): Promise<SaveResult> {
  const { error, response } = await api.POST('/api/v1/digital-timesheets/{timesheetId}/lines', {
    params: { path: { timesheetId } },
    body,
  });
  return response.ok ? { ok: true } : failed(response.status, error);
}

export async function changeLine(api: Api, lineId: string, body: DigitalTimesheetLineChange): Promise<SaveResult> {
  const { error, response } = await api.PUT('/api/v1/digital-timesheet-lines/{lineId}', { params: { path: { lineId } }, body });
  return response.ok ? { ok: true } : failed(response.status, error);
}

export async function deleteLine(api: Api, lineId: string): Promise<SaveResult> {
  const { error, response } = await api.DELETE('/api/v1/digital-timesheet-lines/{lineId}', { params: { path: { lineId } } });
  return response.ok ? { ok: true } : failed(response.status, error);
}
