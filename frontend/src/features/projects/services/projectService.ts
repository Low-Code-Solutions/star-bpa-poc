import type { Api } from '@/services/apiClient';
import type { Project } from '@/types/api';
import type { MasterDataResult } from '../projects.types';

// The Projects and master data API (BPA-77; any logged-in group). React never calls Camunda; the backend checks the scope (403 for a project that is not the user's).

/** GET /api/v1/me/projects: the user's own projects, by reference; null when the call failed, [] when the user has none. */
export async function listMyProjects(api: Api): Promise<Project[] | null> {
  try {
    const { data } = await api.GET('/api/v1/me/projects');
    return data ?? null;
  } catch {
    return null;
  }
}

/** GET /api/v1/projects/{projectRef}/master-data: the five tabs of one project, or the refusal. */
export async function getProjectMasterData(api: Api, projectRef: string): Promise<MasterDataResult> {
  try {
    const { data, error, response } = await api.GET('/api/v1/projects/{projectRef}/master-data', {
      params: { path: { projectRef } },
    });
    if (response.ok && data) {
      return { ok: true, data };
    }
    return { ok: false, status: response.status, detail: (error as { detail?: string } | undefined)?.detail };
  } catch {
    return { ok: false, status: 0 };
  }
}
