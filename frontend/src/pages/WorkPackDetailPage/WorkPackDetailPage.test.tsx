import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/App';

// BPA-73: the Work Pack detail (read only) at /workpacks/<number>: a summary panel and a lifecycle bar, five tabs (Overview, Workers, Hours, Progress,
// History) and a side panel of the open tasks from every group, of which a user can open only the ones of their own groups. The backend is a fetch
// mock that answers like GET /api/v1/workpacks/{n}, .../history and .../open-tasks do (WorkPackDetailApiTest proves the real ones).
const SUMMARY = {
  workpackNumber: 'WP-000457',
  activityRef: 'A-2201',
  drawingRef: 'DWG-118',
  departmentRef: 'Fabrication',
  projectRef: 'PRJ-139',
  status: 'In Progress',
  assignmentState: 'Assigned',
  plannedStartDate: '2026-11-03',
  plannedEndDate: '2026-11-06',
  approvedPct: 35,
  approvedWeekEnding: '2026-11-19',
  earnedManhours: 175,
};

const DETAIL_457 = {
  summary: SUMMARY,
  overview: {
    completionDate: '2026-11-30',
    phases: [
      { phase: 'Cutting', manhours: 150 },
      { phase: 'Welding', manhours: 300 },
    ],
    materials: [{ materialRef: 'MAT-PIPE-06', phase: 'Cutting' }],
    schedule: { plannedStartDate: '2026-11-03', plannedEndDate: '2026-11-06', scheduledBy: 'production-planner' },
  },
  workers: [
    { badgeNo: 'E1021', workerName: 'Worker E1021', role: 'Welder', designation: 'Grade 3', estimatedHours: 100, hoursLoadedNotRemoved: false, journalStatus: 'Open' },
    { badgeNo: 'E1099', workerName: 'Worker E1099', role: 'Fitter', designation: 'Grade 2', estimatedHours: 100, hoursLoadedNotRemoved: true, journalStatus: 'Open' },
  ],
  hours: {
    lines: [
      { badgeNo: 'E1021', workDate: '2026-11-03', phase: 'Cutting', hours: 8, status: 'Approved', entrySource: 'Form' },
      { badgeNo: 'E1021', workDate: '2026-11-04', phase: 'Cutting', hours: 9.5, status: 'Approved', entrySource: 'Form' },
      { badgeNo: 'E1099', workDate: '2026-11-03', phase: 'Welding', hours: 10, status: 'Approved', entrySource: 'Form' },
      { badgeNo: 'E1099', workDate: '2026-11-05', phase: 'Welding', hours: 4, status: 'Rejected', entrySource: 'Form' },
    ],
    totalHours: 27.5,
  },
  progress: [
    {
      weekEnding: '2026-11-19',
      periodStatus: 'Locked',
      reportedPct: 35,
      approvedPct: 35,
      ruleResult: 'Accepted',
      earnedManhours: 175,
      retractions: [],
    },
  ],
};

const DETAIL_490 = {
  summary: { ...SUMMARY, workpackNumber: 'WP-000490', status: 'Draft', assignmentState: undefined, plannedStartDate: undefined, plannedEndDate: undefined, approvedPct: undefined },
  overview: { completionDate: '2026-12-31', phases: [{ phase: 'Cutting', manhours: 80 }], materials: [] },
  workers: [],
  hours: { lines: [], totalHours: 0 },
  progress: [],
};

const OPEN_TASK = {
  key: '2251799813700001',
  name: 'Approve hours (Production)',
  elementId: 'UserTask_ApproveHoursProduction',
  candidateGroups: ['production-supervisor'],
  creationDate: '2026-11-19T07:00:00Z',
};

function fakeBackend(groups: string[], { tasksStatus = 200 }: { tasksStatus?: number } = {}) {
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    if (path === '/api/v1/me') {
      return json(200, { username: groups[0], groups });
    }
    if (path === '/api/v1/workpacks/WP-000457') {
      return json(200, DETAIL_457);
    }
    if (path === '/api/v1/workpacks/WP-000490') {
      return json(200, DETAIL_490);
    }
    if (path === '/api/v1/workpacks/WP-000457/open-tasks') {
      return tasksStatus === 200
        ? json(200, [{ ...OPEN_TASK, canOpen: groups.includes('production-supervisor') }])
        : json(tasksStatus, { status: tasksStatus, detail: 'The process engine is not available right now; try again later' }, 'application/problem+json');
    }
    if (path === '/api/v1/workpacks/WP-000457/history') {
      return json(200, {
        items: [
          { kind: 'Change', at: '2026-11-04T08:00:00Z', username: 'ppc-team', tableName: 'workpack', columnName: 'status', oldValue: 'Released', newValue: 'In Progress' },
        ],
        total: 1,
      });
    }
    if (path.startsWith('/api/v1/workpacks/')) {
      return json(404, { status: 404, title: 'Not Found', detail: 'Work Pack not found' }, 'application/problem+json');
    }
    return json(200, []);
  });
  return { fetchMock };
}

async function openDetail(number: string, groups: string[] = ['ppc-team'], options: Parameters<typeof fakeBackend>[1] = {}) {
  const backend = fakeBackend(groups, options);
  vi.stubGlobal('fetch', backend.fetchMock);
  window.history.pushState({}, '', `/workpacks/${number}`);
  const user = userEvent.setup();
  render(<App />);
  return { ...backend, user };
}

describe('WorkPackDetailPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.pushState({}, '', '/');
  });

  it('BPA73_overviewShowsStatusAndPhases', async () => {
    await openDetail('WP-000457');

    const bar = await screen.findByRole('list', { name: 'Lifecycle' });
    const steps = within(bar).getAllByRole('listitem');
    expect(steps.map((s) => s.textContent)).toEqual([expect.stringContaining('Draft'), expect.stringContaining('Released'), expect.stringContaining('In Progress'), expect.stringContaining('Completed')]);
    expect(steps[2]).toHaveAttribute('aria-current', 'step');
    expect(steps[0]).toHaveClass('lifecycle__step--done');
    expect(steps[1]).toHaveClass('lifecycle__step--done');
    expect(steps[3]).not.toHaveClass('lifecycle__step--done');
    const overview = screen.getByRole('tabpanel', { name: 'Overview' });
    expect(within(overview).getByText('Cutting')).toBeInTheDocument();
    expect(within(overview).getByText('Welding')).toBeInTheDocument();
    expect(within(overview).getByText(/03 Nov 2026/)).toBeInTheDocument();
    expect(within(overview).getByText(/06 Nov 2026/)).toBeInTheDocument();
  });

  it('BPA73_workersTabShowsKeptWorker', async () => {
    const { user } = await openDetail('WP-000457');
    await screen.findByRole('tablist');

    await user.click(screen.getByRole('tab', { name: 'Workers' }));

    const row = within(screen.getByRole('tabpanel', { name: 'Workers' })).getByRole('row', { name: /E1099/ });
    expect(row).toHaveTextContent('Hours loaded; not removed');
    expect(within(screen.getByRole('tabpanel', { name: 'Workers' })).getByRole('row', { name: /E1021/ })).not.toHaveTextContent('Hours loaded; not removed');
  });

  it('BPA73_hoursTabExcludesRejectedFromTotal', async () => {
    const { user } = await openDetail('WP-000457');
    await screen.findByRole('tablist');

    await user.click(screen.getByRole('tab', { name: 'Hours' }));

    const panel = screen.getByRole('tabpanel', { name: 'Hours' });
    expect(within(panel).getAllByRole('row')).toHaveLength(5); // the header and the four lines
    expect(within(panel).getByRole('row', { name: /E1099.*Rejected/ })).toBeInTheDocument();
    expect(within(panel).getByText('Total: 27.50 h')).toBeInTheDocument();
  });

  it('BPA73_progressTabShowsLockedWeek', async () => {
    const { user } = await openDetail('WP-000457');
    await screen.findByRole('tablist');

    await user.click(screen.getByRole('tab', { name: 'Progress' }));

    const row = within(screen.getByRole('tabpanel', { name: 'Progress' })).getByRole('row', { name: /19 Nov 2026/ });
    expect(row).toHaveTextContent('Locked');
    expect(row).toHaveTextContent('35.00');
    expect(row).toHaveTextContent('175.00');
  });

  it('BPA73_draftWorkPackHasNothingYet', async () => {
    const { user } = await openDetail('WP-000490');
    await screen.findByRole('tablist');

    await user.click(screen.getByRole('tab', { name: 'Hours' }));
    expect(within(screen.getByRole('tabpanel', { name: 'Hours' })).getByText('No hours booked yet')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Progress' }));
    expect(within(screen.getByRole('tabpanel', { name: 'Progress' })).getByText('No progress recorded yet')).toBeInTheDocument();
  });

  it('BPA73_unknownWorkPack', async () => {
    await openDetail('WP-999999');

    expect(await screen.findByText('Work Pack not found')).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('BPA73_openTaskOnlyForOwnGroup', async () => {
    const { user } = await openDetail('WP-000457', ['timekeeper']);
    const panel = await screen.findByRole('complementary', { name: 'Open tasks' });

    // timekeeper: the task is listed but there is nothing to open it with
    expect(await within(panel).findByText(/Approve hours \(Production\)/)).toBeInTheDocument();
    expect(within(panel).getByText(/production-supervisor/)).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: /Open/ })).not.toBeInTheDocument();
    cleanup();

    // production-supervisor: the same task is a button
    await openDetail('WP-000457', ['production-supervisor']);
    const own = await screen.findByRole('complementary', { name: 'Open tasks' });
    expect(await within(own).findByRole('button', { name: /Open Approve hours \(Production\)/ })).toBeEnabled();
    expect(user).toBeDefined();
  });

  // --- not scenarios (plan BPA-73) ---------------------------------------------------------------------------------------------------

  it('theHistoryTabShowsTheChanges', async () => {
    const { user } = await openDetail('WP-000457');
    await screen.findByRole('tablist');

    await user.click(screen.getByRole('tab', { name: 'History' }));

    const panel = screen.getByRole('tabpanel', { name: 'History' });
    expect(await within(panel).findByText(/status/)).toBeInTheDocument();
    expect(within(panel).getByText(/In Progress/)).toBeInTheDocument();
  });

  it('theSidePanelFailsAloneWhenTheEngineIsDown', async () => {
    await openDetail('WP-000457', ['ppc-team'], { tasksStatus: 503 });

    const panel = await screen.findByRole('complementary', { name: 'Open tasks' });
    expect(await within(panel).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('tablist')).toBeInTheDocument(); // the page itself still shows
  });

  it('thePageOffersNoActionToChangeTheWorkPack', async () => {
    await openDetail('WP-000457', ['timekeeper']);
    await screen.findByRole('tablist');

    const main = within(screen.getByRole('main'));
    await waitFor(() => expect(main.getByRole('tabpanel')).toBeInTheDocument());
    expect(main.queryAllByRole('button', { name: /create|edit|new|delete|save|release|approve week/i })).toHaveLength(0);
  });
});
