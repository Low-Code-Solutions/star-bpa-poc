import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/App';

// BPA-72: the Work Packs list, read only, under Home. It is reached from the Sidebar (or /workpacks), shows every Work Pack in a table, filters on
// status, project and week ending, searches by number, and a click on a row opens /workpacks/<number> (BPA-73 builds that page; here a placeholder).
// The backend is a fetch mock that filters like GET /api/v1/workpacks does (WorkPackListApiTest proves the real one). Each test renders the whole
// <App /> at /workpacks, so the route, the Sidebar and the page are tested together.
const ROWS = [
  {
    workpackNumber: 'WP-000480',
    activityRef: 'A-2201',
    drawingRef: 'DWG-118',
    departmentRef: 'Fabrication',
    projectRef: 'PRJ-139',
    status: 'Released',
    assignmentState: 'Assigned',
    plannedStartDate: '2026-11-16',
    plannedEndDate: '2026-11-18',
    approvedPct: null,
    approvedWeekEnding: null,
    earnedManhours: null,
  },
  {
    workpackNumber: 'WP-000461',
    activityRef: 'A-2201',
    drawingRef: 'DWG-121',
    departmentRef: 'Fabrication',
    projectRef: 'PRJ-139',
    status: 'Completed',
    assignmentState: 'Assigned',
    plannedStartDate: '2026-11-10',
    plannedEndDate: '2026-11-12',
    approvedPct: 100,
    approvedWeekEnding: '2026-11-12',
    earnedManhours: 150,
  },
  {
    workpackNumber: 'WP-000457',
    activityRef: 'A-2201',
    drawingRef: 'DWG-118',
    departmentRef: 'Fabrication',
    projectRef: 'PRJ-139',
    status: 'In Progress',
    assignmentState: 'Assigned',
    plannedStartDate: '2026-11-03',
    plannedEndDate: '2026-11-06',
    approvedPct: 30,
    approvedWeekEnding: '2026-11-12',
    earnedManhours: 150,
  },
];

// BPA-79: a Work Pack of a project the user does not have. The real backend limits the list to the user's projects (WorkPackReadScopeApiTest); this fake does the
// same when a test gives the user's projects, and lists only ROWS (all PRJ-139) when it does not, as every test above.
const OTHER_ROW = {
  workpackNumber: 'WP-000500',
  activityRef: 'A-4201',
  drawingRef: 'DWG-118',
  departmentRef: 'Fabrication',
  projectRef: 'PRJ-142',
  status: 'Released',
  assignmentState: 'Assigned',
  plannedStartDate: '2026-11-16',
  plannedEndDate: '2026-11-18',
  approvedPct: null,
  approvedWeekEnding: null,
  earnedManhours: null,
};
const rowsFor = (projects?: string[]) => (projects ? [...ROWS, OTHER_ROW].filter((r) => projects.includes(r.projectRef)) : ROWS);

function fakeBackend(groups: string[], projects?: string[]) {
  const listCalls: URLSearchParams[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const url = new URL(input.url);
    if (url.pathname === '/api/v1/me') {
      return json(200, { username: groups[0], groups });
    }
    if (url.pathname === '/api/v1/workpacks' && input.method === 'GET') {
      listCalls.push(url.searchParams);
      const statuses = url.searchParams.getAll('status');
      const project = url.searchParams.get('project');
      const search = (url.searchParams.get('search') ?? '').toLowerCase();
      return json(
        200,
        rowsFor(projects).filter(
          (r) =>
            (statuses.length === 0 || statuses.includes(r.status)) &&
            (!project || r.projectRef === project) &&
            r.workpackNumber.toLowerCase().includes(search),
        ),
      );
    }
    if (url.pathname === '/api/v1/workpacks/WP-000457') {
      return json(200, {
        summary: ROWS[2],
        overview: { completionDate: '2026-11-30', phases: [], materials: [] },
        workers: [],
        hours: { lines: [], totalHours: 0 },
        progress: [],
      });
    }
    return json(200, []);
  });
  return { fetchMock, listCalls };
}

async function openList(groups: string[] = ['ppc-team'], projects?: string[]) {
  const backend = fakeBackend(groups, projects);
  vi.stubGlobal('fetch', backend.fetchMock);
  window.history.pushState({}, '', '/workpacks');
  const user = userEvent.setup();
  render(<App />);
  return { ...backend, user };
}

const numbersShown = () =>
  within(screen.getByRole('table', { name: 'Work Packs' }))
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell')[0].textContent);

describe('WorkPacksPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.pushState({}, '', '/');
  });

  it('BPA72_listShowsEveryWorkPack', async () => {
    await openList();

    const table = await screen.findByRole('table', { name: 'Work Packs' });
    await waitFor(() => expect(numbersShown()).toEqual(['WP-000480', 'WP-000461', 'WP-000457']));
    const row457 = within(table).getByRole('row', { name: /WP-000457/ });
    const cells = within(row457)
      .getAllByRole('cell')
      .map((c) => c.textContent);
    expect(cells).toContain('In Progress');
    expect(cells).toContain('Assigned');
    expect(cells).toContain('03 Nov 2026');
    expect(cells).toContain('06 Nov 2026');
    expect(within(table).getByRole('row', { name: /WP-000461/ })).toHaveTextContent('Completed');
    expect(within(table).getByRole('row', { name: /WP-000480/ })).toHaveTextContent('Released');
  });

  it('BPA72_filterByStatus', async () => {
    const { user, listCalls } = await openList();
    await screen.findByRole('table', { name: 'Work Packs' });

    await user.selectOptions(screen.getByLabelText('Status'), 'In Progress');

    await waitFor(() => expect(numbersShown()).toEqual(['WP-000457']));
    expect(listCalls.some((p) => p.getAll('status').join() === 'In Progress')).toBe(true);
  });

  it('BPA72_searchByNumber', async () => {
    const { user } = await openList();
    await screen.findByRole('table', { name: 'Work Packs' });

    await user.type(screen.getByLabelText('Work Pack number'), 'WP-000461');

    await waitFor(() => expect(numbersShown()).toEqual(['WP-000461']));
    expect(
      within(screen.getByRole('table', { name: 'Work Packs' }).querySelectorAll('tbody tr')[0] as HTMLElement).getByText(
        'Completed',
      ),
    ).toBeInTheDocument();
  });

  it('BPA72_unknownNumber', async () => {
    const { user } = await openList();
    await screen.findByRole('table', { name: 'Work Packs' });

    await user.type(screen.getByLabelText('Work Pack number'), 'WP-999999');

    expect(await screen.findByText('Work Pack not found')).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /WP-0004/ })).not.toBeInTheDocument();
  });

  it('BPA72_openWorkPackFromList', async () => {
    const { user } = await openList();
    const table = await screen.findByRole('table', { name: 'Work Packs' });

    await user.click(await within(table).findByRole('row', { name: /WP-000457/ }));

    // BPA-73 builds the detail page; until then a placeholder with the number is the page that opens
    expect(await screen.findByText(/Work Pack WP-000457/)).toBeInTheDocument();
    expect(window.location.pathname).toBe('/workpacks/WP-000457');
  });

  it('BPA72_anyPersonaReadsWithoutActions', async () => {
    await openList(['timekeeper']);

    const table = await screen.findByRole('table', { name: 'Work Packs' });
    await waitFor(() => expect(numbersShown()).toHaveLength(3));
    // the Sidebar offers the list to every logged-in group
    expect(screen.getByRole('link', { name: 'Work Packs' })).toBeInTheDocument();
    // read only: nothing on the page offers to create, edit or delete (Log out belongs to the Header, not to the list)
    const main = within(screen.getByRole('main'));
    expect(main.queryAllByRole('button', { name: /create|edit|new|delete|start/i })).toHaveLength(0);
    expect(main.queryAllByRole('link', { name: /create|edit|new|delete|start/i })).toHaveLength(0);
    expect(within(table).queryAllByRole('button')).toHaveLength(0);
  });

  // BPA-79: the backend limits the list to the user's projects, so the table shows only those Work Packs and the Project filter (built from the rows) offers only those
  // projects. Passes before BPA-79's code: the filter already follows the rows (a guard, said so); WorkPackReadScopeApiTest is the real test of the limit.
  it('BPA79_listFilteredByProject', async () => {
    await openList(['workpack-owner'], ['PRJ-139']);

    await screen.findByRole('table', { name: 'Work Packs' });
    await waitFor(() => expect(numbersShown()).toEqual(['WP-000480', 'WP-000461', 'WP-000457']));
    expect(numbersShown()).not.toContain('WP-000500');
    const filter = screen.getByLabelText('Project');
    expect(
      within(filter)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['All', 'PRJ-139']);
  });
});
