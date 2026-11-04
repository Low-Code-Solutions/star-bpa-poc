import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { Api } from '@/services/apiClient';
import type { Me } from '@/types/api';
import type { AuthState } from '@/features/auth/auth.types';
import * as authService from '@/features/auth/services/authService';

// The only React context (ADR 0017: no store). It holds the session user read once at login (S5, S6), nothing else.
const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ api, children }: { api: Api; children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    authService.fetchMe(api).then((user) => {
      setMe(user);
      setLoading(false);
    });
  }, [api]);

  const login = useCallback(
    async (username: string, password: string) => {
      const result = await authService.login(api, username, password);
      if ('me' in result) {
        setMe(result.me);
      }
      return result;
    },
    [api],
  );

  const logout = useCallback(async () => {
    await authService.logout(api);
    setMe(null);
  }, [api]);

  const value = useMemo(() => ({ me, loading, login, logout }), [me, loading, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const auth = useContext(AuthContext);
  if (!auth) {
    throw new Error('useAuth needs an AuthProvider');
  }
  return auth;
}
