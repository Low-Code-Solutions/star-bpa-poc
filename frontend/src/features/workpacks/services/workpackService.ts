import type { Api } from '@/services/apiClient';
import type {
  LookAhead,
  OpenTask,
  WorkPackDetail,
  WorkPackHistoryPage,
  WorkPackListRow,
  WorkPackPrintSummary,
  WorkPackStatus,
} from '@/types/api';
import type { StartResult } from '../workpacks.types';

/** POST /api/v1/workpacks (BPA-6, D52, S20; BPA-78): starts a Work Pack request for one of the user's projects; a refusal answers its status and detail. */
export async function startWorkPack(api: Api, projectRef: string): Promise<StartResult> {
  const { data, error, response } = await api.POST('/api/v1/workpacks', { body: { projectRef } });
  if (!response.ok || !data) {
    return { status: response.status, detail: (error as { detail?: string } | undefined)?.detail };
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
  try {
    const { data } = await api.GET('/api/v1/workpack-prints');
    return data ?? null;
  } catch {
    // A network failure is a failed call too: the screen says so instead of showing nothing.
    return null;
  }
}

/** The outcome of fetching a print: the PDF as a blob, or the status and the contract's problem `detail`. */
export type PrintResult = { ok: true; blob: Blob } | { ok: false; status: number; detail?: string };

/**
 * GET /api/v1/workpacks/{workpackNumber}/print (BPA-10; 09 S4): the stored PDF as a blob, to show in the browser's viewer. A 404 says
 * "Workpack print not available" (not scheduled yet) or "Work Pack not found".
 */
export async function getWorkPackPrint(api: Api, workpackNumber: string): Promise<PrintResult> {
  try {
    const { data, error, response } = await api.GET('/api/v1/workpacks/{workpackNumber}/print', {
      params: { path: { workpackNumber } },
      parseAs: 'blob',
    });
    if (response.ok && data) {
      return { ok: true, blob: data };
    }
    const detail =
      typeof error === 'object' && error !== null && 'detail' in error
        ? String((error as { detail: unknown }).detail)
        : undefined;
    return { ok: false, status: response.status, detail };
  } catch {
    // The request itself failed (network): status 0, so the screen shows its general message.
    return { ok: false, status: 0 };
  }
}

/** The filters of the Work Packs list (BPA-72); an empty text is "no filter". */
export type WorkPackFilter = { status?: string; project?: string; weekEnding?: string; search?: string };

/**
 * GET /api/v1/workpacks (BPA-72): the Work Packs list, read only, filtered on the server; null when the call failed. The screen sends one status at a
 * time (the contract takes several).
 */
export async function listWorkPacks(api: Api, filter: WorkPackFilter): Promise<WorkPackListRow[] | null> {
  try {
    const { data } = await api.GET('/api/v1/workpacks', {
      params: {
        query: {
          status: filter.status ? [filter.status as WorkPackStatus] : undefined,
          project: filter.project || undefined,
          weekEnding: filter.weekEnding || undefined,
          search: filter.search?.trim() || undefined,
        },
      },
    });
    return data ?? null;
  } catch {
    return null;
  }
}

/** The detail of a Work Pack (BPA-73), or why there is none: a 404 means "Work Pack not found"; a 403 (BPA-79, the Work Pack is of a project the user does not have) carries the backend's text; null is a failed call. */
export type WorkPackResult = { detail: WorkPackDetail } | { notFound: true } | { forbidden: string } | null;

/** GET /api/v1/workpacks/{workpackNumber} (BPA-73): the read-only Work Pack detail. */
export async function getWorkPack(api: Api, workpackNumber: string): Promise<WorkPackResult> {
  try {
    const { data, error, response } = await api.GET('/api/v1/workpacks/{workpackNumber}', {
      params: { path: { workpackNumber } },
    });
    if (response.status === 404) {
      return { notFound: true };
    }
    if (response.status === 403) {
      return { forbidden: (error as { detail?: string } | undefined)?.detail ?? 'You cannot open this Work Pack' };
    }
    return data ? { detail: data } : null;
  } catch {
    return null;
  }
}

/** GET /api/v1/workpacks/{workpackNumber}/history (BPA-73): one page of the history, newest first; null when the call failed. */
export async function getWorkPackHistory(api: Api, workpackNumber: string, page: number): Promise<WorkPackHistoryPage | null> {
  try {
    const { data } = await api.GET('/api/v1/workpacks/{workpackNumber}/history', {
      params: { path: { workpackNumber }, query: { page, size: HISTORY_PAGE_SIZE } },
    });
    return data ?? null;
  } catch {
    return null;
  }
}

export const HISTORY_PAGE_SIZE = 50;

/** GET /api/v1/workpacks/{workpackNumber}/open-tasks (BPA-73): the open tasks of every group, no task data; null when the call failed (503 included). */
export async function listWorkPackOpenTasks(api: Api, workpackNumber: string): Promise<OpenTask[] | null> {
  try {
    const { data } = await api.GET('/api/v1/workpacks/{workpackNumber}/open-tasks', { params: { path: { workpackNumber } } });
    return data ?? null;
  } catch {
    return null;
  }
}
