import type { Api } from '@/services/apiClient';
import type { Me } from '@/types/api';
import type { LoginResult } from '../auth.types';

/** GET /api/v1/me. Also gives the browser its XSRF-TOKEN cookie before the first POST (S26). */
export async function fetchMe(api: Api): Promise<Me | null> {
  const { data } = await api.GET('/api/v1/me');
  return data ?? null;
}

/** POST /api/v1/auth/login (S5): the backend checks the password with Camunda once and starts a session. */
export async function login(api: Api, username: string, password: string): Promise<LoginResult> {
  const { data, response } = await api.POST('/api/v1/auth/login', { body: { username, password } });
  return data ? { me: data } : { status: response.status };
}

export async function logout(api: Api): Promise<void> {
  await api.POST('/api/v1/auth/logout');
}
