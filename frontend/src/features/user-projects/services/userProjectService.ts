import type { Api } from '@/services/apiClient';
import type { PortalUser, Project } from '@/types/api';
import type { ChangeResult } from '../user-projects.types';

// The Users and projects API (BPA-76; ppc-team only). React never calls Camunda; the backend answers the story's messages.
// A list is undefined while it loads (the hooks), null when the call failed, and [] when there is nothing.

const failed = (status: number, error: unknown): ChangeResult => ({
  ok: false,
  status,
  detail: (error as { detail?: string } | undefined)?.detail,
});

/** GET /api/v1/users: every portal user with its Camunda groups. */
export async function listUsers(api: Api): Promise<PortalUser[] | null> {
  try {
    const { data } = await api.GET('/api/v1/users');
    return data ?? null;
  } catch {
    return null;
  }
}

/** GET /api/v1/projects: the projects that can be assigned. */
export async function listProjects(api: Api): Promise<Project[] | null> {
  try {
    const { data } = await api.GET('/api/v1/projects');
    return data ?? null;
  } catch {
    return null;
  }
}

/** GET /api/v1/users/{username}/projects: the projects the user has. */
export async function listUserProjects(api: Api, username: string): Promise<Project[] | null> {
  try {
    const { data } = await api.GET('/api/v1/users/{username}/projects', { params: { path: { username } } });
    return data ?? null;
  } catch {
    return null;
  }
}

export async function assignProject(api: Api, username: string, projectRef: string): Promise<ChangeResult> {
  const { error, response } = await api.POST('/api/v1/users/{username}/projects', {
    params: { path: { username } },
    body: { projectRef },
  });
  return response.ok ? { ok: true } : failed(response.status, error);
}

export async function unassignProject(api: Api, username: string, projectRef: string): Promise<ChangeResult> {
  const { error, response } = await api.DELETE('/api/v1/users/{username}/projects/{projectRef}', {
    params: { path: { username, projectRef } },
  });
  return response.ok ? { ok: true } : failed(response.status, error);
}
