import type { Api } from '@/services/apiClient';
import type { CompletionReportResult } from '@/types/api';

// The completion report upload API (BPA-59; E2-09; the BPA-52 pattern). React never calls Camunda; the backend checks every row.

/** The outcome of an upload: the result, or the status and the contract's problem `detail` (400 "File is required", 413 "File is larger than 5 MB", 404). */
export type UploadResult = { ok: true; result: CompletionReportResult } | { ok: false; status: number; detail?: string };

function detailOf(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null && 'detail' in error
    ? String((error as { detail: unknown }).detail)
    : undefined;
}

/**
 * POST /api/v1/completion-reports: the report (.xlsx or .csv) and the key of the open Upload completion report task as multipart/form-data (the task says
 * which week the report belongs to, plan Q8). A 201 carries the result, every row with its outcome, or one file-level line.
 */
export async function uploadCompletionReport(api: Api, file: File, taskKey: string): Promise<UploadResult> {
  try {
    const { data, error, response } = await api.POST('/api/v1/completion-reports', {
      // The schema types the file as a string (binary); the serializer sends the real File.
      body: { file: '', taskKey },
      bodySerializer: () => {
        const form = new FormData();
        form.append('file', file);
        form.append('taskKey', taskKey);
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
