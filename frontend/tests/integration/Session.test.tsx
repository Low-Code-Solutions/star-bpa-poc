import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/App';

// BPA-47 review: the portal keeps no server data across sessions, and keeps its URL (no redirect). The backend is a fetch
// stub with two personas: the analyst has one open task, the production team member none.
const TASKS: Record<string, unknown[]> = {
  'time-method-analyst': [
    {
      key: '2251799813690093',
      name: 'Create Work Pack',
      elementId: 'UserTask_CreateWorkPack',
      processInstanceKey: '2251799813690086',
      creationDate: '2026-10-21T06:00:00Z',
    },
  ],
  'production-team': [],
};

/** gate: the production team member's task list is held until the test releases it, so a stale list would stay visible. */
function fakeBackend(gate: Promise<void> = Promise.resolve()) {
  let session: { username: string; groups: string[] } | null = null;
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  return vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    if (path === '/api/v1/me') {
      return session ? json(200, session) : json(401, { status: 401 });
    }
    if (path === '/api/v1/auth/login') {
      const { username } = JSON.parse(await input.clone().text()) as { username: string };
      session = { username, groups: [username] };
      return json(200, session);
    }
    if (path === '/api/v1/auth/logout') {
      session = null;
      return new Response(null, { status: 204 });
    }
    if (path === '/api/v1/tasks') {
      if (session?.username === 'production-team') {
        await gate;
      }
      return session ? json(200, TASKS[session.username] ?? []) : json(401, { status: 401 });
    }
    return json(404, {});
  });
}

async function logIn(user: ReturnType<typeof userEvent.setup>, username: string) {
  await user.type(await screen.findByLabelText('Username'), username);
  await user.type(screen.getByLabelText('Password'), 'right');
  await user.click(screen.getByRole('button', { name: 'Log in' }));
  await screen.findByText(/Logged in as/);
}

describe('Session', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.replaceState(null, '', '/');
  });

  it("does not show the previous user's tasks after logout and a new login", async () => {
    let release = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    vi.stubGlobal('fetch', fakeBackend(gate));
    const user = userEvent.setup();
    render(<App />);

    await logIn(user, 'time-method-analyst');
    expect(await screen.findByRole('button', { name: 'Create Work Pack' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Log out' }));
    await logIn(user, 'production-team');

    // While the new user's list is still on its way: never the analyst's task.
    expect(screen.queryByRole('button', { name: 'Create Work Pack' })).not.toBeInTheDocument();
    release();
    expect(await within(screen.getByRole('region', { name: 'My tasks' })).findByText('No open tasks')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create Work Pack' })).not.toBeInTheDocument();
  });

  it('renders the portal at any URL and keeps the URL', async () => {
    window.history.replaceState(null, '', '/some/old/link');
    vi.stubGlobal('fetch', fakeBackend());
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByLabelText('Username')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/some/old/link');
    await logIn(user, 'time-method-analyst');

    expect(await screen.findByRole('region', { name: 'My tasks' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/some/old/link');
  });
});
