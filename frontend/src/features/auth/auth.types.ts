import type { Me } from '@/types/api';

/** What the login call answers: the user, or the HTTP status of the refusal. */
export type LoginResult = { me: Me } | { status: number };

export type AuthState = {
  /** The logged-in user and the Camunda groups read at login (S5, S6), or null. */
  me: Me | null;
  /** True until the first GET /api/v1/me has answered. */
  loading: boolean;
  login: (username: string, password: string) => Promise<LoginResult>;
  logout: () => Promise<void>;
};
