import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/App';

// BPA-76: "Users and projects", a page for the PPC Team only. Pick a user, see the projects of that user, assign a project or unassign one. It is
// reached from the Sidebar (shown to ppc-team only) or at /users. The backend is a fetch mock that keeps the assignments like the real endpoints do
// (UserProjectApiTest proves those); each test renders the whole <App /> at /users, so the route, the Sidebar and the page are tested together. The
// five BPA76_ names are the five Gherkin scenarios, as in UserProjectApiTest (Jira gives no names, so they follow docs/04).
const USERS = [
  { username: 'project-engineer', employeeRef: 'E9002', email: 'project-engineer@example.com', groups: ['project-engineer'] },
  { username: 'timekeeper', employeeRef: 'E9008', email: 'timekeeper@example.com', groups: ['timekeeper'] },
];
// PRJ-999 is in the list on purpose: a project that was removed from D365 after the screen loaded, so the backend still refuses it (scenario 3).
const PROJECTS = ['PRJ-139', 'PRJ-140', 'PRJ-142', 'PRJ-999'].map((projectRef) => ({ projectRef }));

type Call = { method: string; path: string; body: unknown };

function fakeBackend(groups: string[], initial: Record<string, string[]> = {}, postDelayMs = 0) {
  const assigned: Record<string, string[]> = { 'project-engineer': [], timekeeper: ['PRJ-139'], ...initial };
  const calls: Call[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const problem = (status: number, detail: string) =>
    json(status, { status, title: 'Problem', detail }, 'application/problem+json');
  const fetchMock = vi.fn(async (input: Request) => {
    const url = new URL(input.url);
    const path = url.pathname;
    if (path === '/api/v1/me') {
      return json(200, { username: groups[0], groups });
    }
    if (path === '/api/v1/users' && input.method === 'GET') {
      return json(200, USERS);
    }
    if (path === '/api/v1/projects' && input.method === 'GET') {
      return json(200, PROJECTS);
    }
    const match = path.match(/^\/api\/v1\/users\/([^/]+)\/projects(?:\/([^/]+))?$/);
    if (match) {
      const [, username, projectRef] = match;
      const body = input.method === 'POST' ? await input.clone().json() : null;
      calls.push({ method: input.method, path, body });
      if (input.method === 'GET') {
        return json(
          200,
          (assigned[username] ?? []).map((p) => ({ projectRef: p })),
        );
      }
      if (input.method === 'POST') {
        if (postDelayMs > 0) {
          await new Promise((resolve) => setTimeout(resolve, postDelayMs)); // a slow backend: the PPC Team can click again before the answer
        }
        const ref = (body as { projectRef: string }).projectRef;
        if (ref === 'PRJ-999') {
          return problem(400, 'Project PRJ-999 does not exist');
        }
        if ((assigned[username] ?? []).includes(ref)) {
          return problem(400, `${ref} is already assigned to ${username}`);
        }
        assigned[username] = [...(assigned[username] ?? []), ref].sort();
        return json(201, { username, projectRef: ref });
      }
      if (input.method === 'DELETE') {
        assigned[username] = (assigned[username] ?? []).filter((p) => p !== projectRef);
        return new Response(null, { status: 204 });
      }
    }
    return json(200, []);
  });
  return { fetchMock, calls, assigned };
}

async function openUsers(groups: string[] = ['ppc-team'], initial: Record<string, string[]> = {}, postDelayMs = 0) {
  const backend = fakeBackend(groups, initial, postDelayMs);
  vi.stubGlobal('fetch', backend.fetchMock);
  window.history.pushState({}, '', '/users');
  const user = userEvent.setup();
  render(<App />);
  return { ...backend, user };
}

async function pickUser(user: ReturnType<typeof userEvent.setup>, username: string) {
  await user.selectOptions(await screen.findByLabelText('User'), username);
}

async function assign(user: ReturnType<typeof userEvent.setup>, projectRef: string) {
  await user.selectOptions(await screen.findByLabelText('Project'), projectRef);
  await user.click(screen.getByRole('button', { name: 'Assign project' }));
}

const projectsShown = (username: string) =>
  within(screen.getByRole('table', { name: `Projects of ${username}` }))
    .queryAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell')[0].textContent);

describe('UsersPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.pushState({}, '', '/');
  });

  it('BPA76_assignTwoProjectsToUser', async () => {
    const { user, calls } = await openUsers();

    await pickUser(user, 'project-engineer');
    await assign(user, 'PRJ-139');
    await assign(user, 'PRJ-142');

    await waitFor(() => expect(projectsShown('project-engineer')).toEqual(['PRJ-139', 'PRJ-142']));
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts.map((c) => c.path)).toEqual([
      '/api/v1/users/project-engineer/projects',
      '/api/v1/users/project-engineer/projects',
    ]);
    expect(posts.map((c) => c.body)).toEqual([{ projectRef: 'PRJ-139' }, { projectRef: 'PRJ-142' }]);
  });

  it('BPA76_unassignAProject', async () => {
    const { user, calls } = await openUsers(['ppc-team'], { 'project-engineer': ['PRJ-139', 'PRJ-142'] });

    await pickUser(user, 'project-engineer');
    await waitFor(() => expect(projectsShown('project-engineer')).toEqual(['PRJ-139', 'PRJ-142']));
    await user.click(screen.getByRole('button', { name: 'Unassign PRJ-142' }));

    await waitFor(() => expect(projectsShown('project-engineer')).toEqual(['PRJ-139']));
    expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.path)).toEqual([
      '/api/v1/users/project-engineer/projects/PRJ-142',
    ]);
  });

  it('BPA76_unknownProjectIsRejected', async () => {
    const { user, assigned } = await openUsers();

    await pickUser(user, 'project-engineer');
    await assign(user, 'PRJ-999');

    expect(await screen.findByRole('alert')).toHaveTextContent('Project PRJ-999 does not exist');
    expect(assigned['project-engineer']).toEqual([]);
    expect(projectsShown('project-engineer')).toEqual([]);
  });

  it('BPA76_duplicateAssignmentIsRejected', async () => {
    const { user, assigned } = await openUsers(['ppc-team'], { 'project-engineer': ['PRJ-139'] });

    await pickUser(user, 'project-engineer');
    await waitFor(() => expect(projectsShown('project-engineer')).toEqual(['PRJ-139']));
    await assign(user, 'PRJ-139');

    expect(await screen.findByRole('alert')).toHaveTextContent('PRJ-139 is already assigned to project-engineer');
    expect(assigned['project-engineer']).toEqual(['PRJ-139']);
  });

  it('BPA76_otherPersonasCannotAssignProjects', async () => {
    const { fetchMock } = await openUsers(['supervisor']);

    await waitFor(() => expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument());
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).queryByRole('link', { name: 'Users and projects' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Users and projects' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('User')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.map(([r]) => new URL((r as Request).url).pathname)).not.toContain('/api/v1/users');
  });

  // --- not scenarios (plan BPA-76) -----------------------------------------------------------------------------------------

  it('ppcTeamSeesTheSidebarLinkAndThePage', async () => {
    await openUsers();

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Users and projects' })).toHaveAttribute('href', '/users');
    expect(await screen.findByRole('heading', { name: 'Users and projects' })).toBeInTheDocument();
  });

  // Resolved Q1 (Ahsan, 24 Nov 2026): the last project can be removed, and the screen says what a restart does (ADR 0005); it is a note, not a rule.
  it('theLastProjectWarningIsShown', async () => {
    const { user } = await openUsers(['ppc-team'], { 'project-engineer': ['PRJ-139'] });

    await pickUser(user, 'project-engineer');

    expect(await screen.findByText(/last project of project-engineer/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Unassign PRJ-139' }));
    await waitFor(() => expect(projectsShown('project-engineer')).toEqual([]));
  });

  it('noWarningWhenTheUserHasTwoProjects', async () => {
    const { user } = await openUsers(['ppc-team'], { 'project-engineer': ['PRJ-139', 'PRJ-142'] });

    await pickUser(user, 'project-engineer');

    await waitFor(() => expect(projectsShown('project-engineer')).toEqual(['PRJ-139', 'PRJ-142']));
    expect(screen.queryByText(/last project/i)).not.toBeInTheDocument();
  });

  // QA review: a double click on Assign must send one request; the second one would be refused as a duplicate and hide the success behind an error.
  it('aDoubleClickOnAssignSendsOneRequest', async () => {
    const { user, calls } = await openUsers(['ppc-team'], {}, 50);

    await pickUser(user, 'project-engineer');
    await user.selectOptions(await screen.findByLabelText('Project'), 'PRJ-139');
    await user.dblClick(screen.getByRole('button', { name: 'Assign project' }));

    await waitFor(() => expect(projectsShown('project-engineer')).toEqual(['PRJ-139']));
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  // QA review: the note must not promise a re-seed that cannot happen. project-engineer has no projects in personas.yaml, so a restart seeds nothing for it.
  it('theLastProjectNoteDoesNotPromiseAReseed', async () => {
    const { user } = await openUsers(['ppc-team'], { 'project-engineer': ['PRJ-139'] });

    await pickUser(user, 'project-engineer');

    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent(/if it has any/i);
    expect(note).not.toHaveTextContent(/seeds the projects of personas\.yaml for this user again/i);
  });
});
