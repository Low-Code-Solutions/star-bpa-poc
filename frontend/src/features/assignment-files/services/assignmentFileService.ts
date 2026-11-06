import type { Api } from '@/services/apiClient';
import type { AssignmentFileResult } from '@/types/api';

/** The outcome of an upload: the result, or the status and the contract's problem `detail` (400 "File is required"). */
export type UploadResult = { ok: true; result: AssignmentFileResult } | { ok: false; status: number; detail?: string };

function detailOf(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null && 'detail' in error
    ? String((error as { detail: unknown }).detail)
    : undefined;
}

/**
 * POST /api/v1/assignment-files (BPA-12, D58): the Worker assignment .xlsx as multipart/form-data. A 201 carries the result, every row
 * with its outcome, or one file-level line. The browser sets the multipart boundary itself.
 */
export async function uploadAssignmentFile(api: Api, file: File): Promise<UploadResult> {
  try {
    const { data, error, response } = await api.POST('/api/v1/assignment-files', {
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

/** GET /api/v1/assignment-files/{id}/result as JSON: the rows with their current outcomes (a row leaves "Pending" once BPA-22 validates it). */
export async function getAssignmentFileResult(api: Api, assignmentFileId: string): Promise<AssignmentFileResult | null> {
  try {
    const { data } = await api.GET('/api/v1/assignment-files/{assignmentFileId}/result', {
      params: { path: { assignmentFileId } },
      headers: { Accept: 'application/json' },
    });
    // The 200 has a JSON and a CSV form in the contract; this call asks for JSON.
    return (data as AssignmentFileResult | undefined) ?? null;
  } catch {
    return null;
  }
}

/** The same result as CSV for the download (the Accept header chooses it, PO decision on BPA-46 Q3); null when the call failed. */
export async function downloadAssignmentFileResultCsv(api: Api, assignmentFileId: string): Promise<Blob | null> {
  try {
    const { data, response } = await api.GET('/api/v1/assignment-files/{assignmentFileId}/result', {
      params: { path: { assignmentFileId } },
      headers: { Accept: 'text/csv' },
      parseAs: 'blob',
    });
    return response.ok && data ? (data as Blob) : null;
  } catch {
    return null;
  }
}

/** GET /api/v1/assignment-files/template: the official template (S18) as a blob; null when the call failed. */
export async function downloadAssignmentTemplate(api: Api): Promise<Blob | null> {
  try {
    const { data, response } = await api.GET('/api/v1/assignment-files/template', { parseAs: 'blob' });
    return response.ok && data ? (data as Blob) : null;
  } catch {
    return null;
  }
}
