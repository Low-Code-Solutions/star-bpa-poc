import { Outlet } from 'react-router';
import { Spinner } from '@/components/common/Spinner';
import { useAuth } from '@/context/AuthContext';
import { LoginPage } from '@/pages/LoginPage';

/**
 * Guards a branch of routes. While /me loads it shows "Loading…"; with nobody logged in it shows the login page in place
 * (no redirect, so the URL does not change); with `groups` given, a user in none of them sees nothing.
 *
 * It only hides screens. The backend's group check (403) is the real control (ADR 0004, 09 S6; ADR 0017).
 */
export function ProtectedRoute({ groups }: { groups?: string[] }) {
  const { me, loading } = useAuth();
  if (loading) {
    return <Spinner />;
  }
  if (!me) {
    return <LoginPage />;
  }
  if (groups && !groups.some((g) => me.groups.includes(g))) {
    return null;
  }
  return <Outlet />;
}
