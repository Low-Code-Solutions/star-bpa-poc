import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/App';

// BPA-43 Part B: the walking-skeleton login screen (L4, ADR 0005). The backend is replaced by a fetch mock that behaves like
// AuthApiTest proves the real one does.
type Call = { method: string; path: string; xsrf: string | null; body: unknown };

function fakeBackend() {
  let session: { username: string; groups: string[] } | null = null;
  const calls: Call[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    const body =
      input.method === 'GET'
        ? null
        : await input
            .clone()
            .text()
            .then((t) => (t ? JSON.parse(t) : null));
    calls.push({ method: input.method, path, xsrf: input.headers.get('X-XSRF-TOKEN'), body });
    if (path === '/api/v1/me') {
      return session ? json(200, session) : json(401, { status: 401, title: 'Unauthorized' }, 'application/problem+json');
    }
    if (path === '/api/v1/auth/login') {
      const { username, password } = body as { username: string; password: string };
      if (username === 'camunda-down') {
        return json(
          503,
          { status: 503, detail: 'Login is not available right now; try again later' },
          'application/problem+json',
        );
      }
      if (username === 'time-method-analyst' && password === 'right') {
        session = { username, groups: ['time-method-analyst'] };
        return json(200, session);
      }
      return json(401, { status: 401, detail: 'Username or password is incorrect' }, 'application/problem+json');
    }
    if (path === '/api/v1/auth/logout') {
      session = null;
      return new Response(null, { status: 204 });
    }
    return json(404, {});
  });
  return { fetchMock, calls };
}

describe('App', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA43_personaLogsInSeesMeAndLogsOut', async () => {
    const { fetchMock, calls } = fakeBackend();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText('Username'), 'time-method-analyst');
    await user.type(screen.getByLabelText('Password'), 'right');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    // BPA-125: the shell shows the persona (name and department), not "Logged in as" and the group list, as the templates do.
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getAllByText(/Time & Method Analyst/).length).toBeGreaterThan(0);
    const login = calls.find((c) => c.path === '/api/v1/auth/login');
    expect(login?.xsrf).toBe('token-123');

    await user.click(screen.getByRole('button', { name: 'Logout' }));

    expect(await screen.findByLabelText('Username')).toBeInTheDocument();
    expect(calls.find((c) => c.path === '/api/v1/auth/logout')?.xsrf).toBe('token-123');
  });

  it('BPA43_wrongPasswordShowsAnError', async () => {
    const { fetchMock } = fakeBackend();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText('Username'), 'time-method-analyst');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Username or password is incorrect');
    expect(screen.queryByRole('button', { name: 'Logout' })).not.toBeInTheDocument();
  });

  it('BPA43_loginServiceDownIsNotShownAsWrongPassword', async () => {
    const { fetchMock } = fakeBackend();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText('Username'), 'camunda-down');
    await user.type(screen.getByLabelText('Password'), 'any');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Login is not available right now');
    expect(alert).not.toHaveTextContent('Username or password is incorrect');
  });
});
