import { afterEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router';
import { cleanup, render, screen } from '@tests/test-utils';
import { AuthProvider } from '@/context/AuthContext';
import { apiClient } from '@/services/apiClient';
import { ProtectedRoute } from './ProtectedRoute';

// BPA-47 (ADR 0017): ProtectedRoute only hides screens; the backend's 403 stays the real check (ADR 0004).
function backendWith(me: { username: string; groups: string[] } | null) {
  return vi.fn(async () =>
    me
      ? new Response(JSON.stringify(me), { status: 200, headers: { 'Content-Type': 'application/json' } })
      : new Response(JSON.stringify({ status: 401 }), { status: 401, headers: { 'Content-Type': 'application/problem+json' } }),
  );
}

function renderGuarded(groups?: string[]) {
  render(
    <AuthProvider api={apiClient()}>
      <Routes>
        <Route element={<ProtectedRoute groups={groups} />}>
          <Route index element={<p>Sign off release screen</p>} />
        </Route>
      </Routes>
    </AuthProvider>,
  );
}

describe('ProtectedRoute', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the login form in place while nobody is logged in', async () => {
    vi.stubGlobal('fetch', backendWith(null));
    renderGuarded(['project-engineer']);

    expect(await screen.findByLabelText('Username')).toBeInTheDocument();
    expect(screen.queryByText('Sign off release screen')).not.toBeInTheDocument();
  });

  it('hides the screen from a user in none of its groups, and shows it to a user in one', async () => {
    vi.stubGlobal('fetch', backendWith({ username: 'time-method-analyst', groups: ['time-method-analyst'] }));
    renderGuarded(['project-engineer']);
    await vi.waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument());
    expect(screen.queryByText('Sign off release screen')).not.toBeInTheDocument();
    cleanup();

    vi.stubGlobal('fetch', backendWith({ username: 'project-engineer', groups: ['project-engineer'] }));
    renderGuarded(['project-engineer']);
    expect(await screen.findByText('Sign off release screen')).toBeInTheDocument();
  });
});
