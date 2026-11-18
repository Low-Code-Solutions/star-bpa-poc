import type { Api } from '@/services/apiClient';
import type { TimesheetFileResult } from '@/types/api';

// The time sheet upload API (BPA-52; E2-03b; the BPA-12 pattern). React never calls Camunda; the backend checks every row.

/** The outcome of an upload: the result, or the status and the contract's problem `detail` (400 "File is required", 413 "File is larger than 5 MB"). */
export type UploadResult = { ok: true; result: TimesheetFileResult } | { ok: false; status: number; detail?: string };

function detailOf(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null && 'detail' in error
    ? String((error as { detail: unknown }).detail)
    : undefined;
}

/**
 * POST /api/v1/timesheet-files: the time sheet (.xlsx or .csv) as multipart/form-data. A 201 carries the result, every row with its outcome, or one
 * file-level line. The browser sets the multipart boundary itself.
 */
export async function uploadTimesheetFile(api: Api, file: File): Promise<UploadResult> {
  try {
    const { data, error, response } = await api.POST('/api/v1/timesheet-files', {
      // The schema types the file as a string (binary); the serializer sends the real File.
      body: { file: '' },
      bodySerializer: () => {
        const form = new FormData();
        form.append('file', file);
        return form;
      },
    });
    if (response.ok && data) {
      return { ok: true, result: data };
    }
    return { ok: false, status: response.status, detail: detailOf(error) };
  } catch {
    return { ok: false, status: 0 };
  }
}

/** The result as CSV for the download (the Accept header chooses it); null when the call failed. */
export async function downloadTimesheetResultCsv(api: Api, timesheetFileId: string): Promise<Blob | null> {
  try {
    const { data, response } = await api.GET('/api/v1/timesheet-files/{timesheetFileId}/result', {
      params: { path: { timesheetFileId } },
      headers: { Accept: 'text/csv' },
      parseAs: 'blob',
    });
    return response.ok && data ? (data as Blob) : null;
  } catch {
    return null;
  }
}

/** GET /api/v1/timesheet-files/template: the official template as a blob; null when the call failed. */
export async function downloadTimesheetTemplate(api: Api): Promise<Blob | null> {
  try {
    const { data, response } = await api.GET('/api/v1/timesheet-files/template', { parseAs: 'blob' });
    return response.ok && data ? (data as Blob) : null;
  } catch {
    return null;
  }
}
