import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/App';

// BPA-77: "Projects and master data", for every persona. A Project picker offers only my projects; five tabs (Activities, Cost structure, Drawings, Reserved
// materials, Cutting sheets) show the master data of the picked project, each with the system of record it comes from; with no project the screen says
// "No projects assigned. Ask the PPC Team.". The backend is a fetch mock that answers like GET /api/v1/me/projects and GET /api/v1/projects/{ref}/master-data
// do (ProjectMasterDataApiTest proves the real ones). Each test renders the whole <App /> at /projects. The four BPA77_ names are the four Gherkin
// scenarios, as in ProjectMasterDataApiTest (Jira gives no names, so they follow docs/04).
type Tab = { systemOfRecord: string; rows: Record<string, unknown>[] };

function masterData(projectRef: string, n: string, drawing: string, trace: string, sheet: string) {
  return {
    projectRef,
    activities: {
      systemOfRecord: 'P6, read through the D365 PMS database',
      rows: [
        { activityRef: `A-${n}`, activityName: `Synthetic activity ${n}`, wbsRef: `W-${n}`, projectRef, jobCodeRef: `JC-${n}` },
      ],
    } as Tab,
    costStructure: {
      systemOfRecord: 'D365 F&O Cost Control Report',
      rows: [{ activityRef: `A-${n}`, activityName: `Synthetic activity ${n}`, cbsCodeRef: `CBS-${n}-PIP-01` }],
    } as Tab,
    drawings: {
      systemOfRecord: 'D365 F&O Document Control',
      rows: [{ drawingRef: drawing, title: `Synthetic ${drawing}`, latestRevision: 'A', status: 'IFC' }],
    } as Tab,
    reservedMaterials: {
      systemOfRecord: 'D365 F&O (PPC)',
      rows: [
        { traceabilityNumber: trace, drawingRef: drawing, materialRef: 'MAT-PIPE-06', description: `Pipe for ${projectRef}` },
      ],
    } as Tab,
    cuttingSheets: {
      systemOfRecord: 'FMS nesting',
      rows: [{ cuttingSheetRef: sheet, drawingRef: drawing }],
    } as Tab,
  };
}

const DATA: Record<string, ReturnType<typeof masterData>> = {
  'PRJ-139': masterData('PRJ-139', '2201', 'DWG-118', 'TR-5531', 'CS-3310'),
  'PRJ-142': masterData('PRJ-142', '4201', 'DWG-1420', 'TR-1420', 'CS-1420'),
};

const TABS: { tab: string; sor: string; mine: string; other: string }[] = [
  { tab: 'Activities', sor: 'P6, read through the D365 PMS database', mine: 'A-4201', other: 'A-2201' },
  { tab: 'Cost structure', sor: 'D365 F&O Cost Control Report', mine: 'CBS-4201-PIP-01', other: 'CBS-2201-PIP-01' },
  { tab: 'Drawings', sor: 'D365 F&O Document Control', mine: 'DWG-1420', other: 'DWG-118' },
  { tab: 'Reserved materials', sor: 'D365 F&O (PPC)', mine: 'TR-1420', other: 'TR-5531' },
  { tab: 'Cutting sheets', sor: 'FMS nesting', mine: 'CS-1420', other: 'CS-3310' },
];

/** `mine` are the projects GET /api/v1/me/projects lists; `refused` are projects the master-data call refuses with 403 (an assignment removed after the list was read). */
function fakeBackend(groups: string[], mine: string[], refused: string[] = []) {
  const calls: string[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const url = new URL(input.url);
    calls.push(`${input.method} ${url.pathname}`);
    if (url.pathname === '/api/v1/me') {
      return json(200, { username: groups[0], groups });
    }
    if (url.pathname === '/api/v1/me/projects') {
      return json(
        200,
        mine.map((projectRef) => ({ projectRef })),
      );
    }
    const match = url.pathname.match(/^\/api\/v1\/projects\/([^/]+)\/master-data$/);
    if (match) {
      const ref = match[1];
      if (refused.includes(ref) || !DATA[ref]) {
        return json(
          403,
          { status: 403, title: 'Forbidden', detail: `Project ${ref} is not assigned to you` },
          'application/problem+json',
        );
      }
      return json(200, DATA[ref]);
    }
    return json(200, []);
  });
  return { fetchMock, calls };
}

async function openProjects(groups: string[], mine: string[], refused: string[] = []) {
  const backend = fakeBackend(groups, mine, refused);
  vi.stubGlobal('fetch', backend.fetchMock);
  window.history.pushState({}, '', '/projects');
  const user = userEvent.setup();
  render(<App />);
  return { ...backend, user };
}

describe('ProjectsPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.pushState({}, '', '/');
  });

  it('BPA77_userSeesOnlyAssignedProjects', async () => {
    await openProjects(['project-engineer'], ['PRJ-139']);

    const picker = await screen.findByLabelText('Project');
    await waitFor(() =>
      expect(
        within(picker)
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['PRJ-139']),
    );
    expect(within(picker).queryByRole('option', { name: 'PRJ-142' })).not.toBeInTheDocument();
  });

  it('BPA77_userWithSeveralProjectsSwitchesProject', async () => {
    const { user } = await openProjects(['time-method-analyst'], ['PRJ-139', 'PRJ-142']);

    await user.selectOptions(await screen.findByLabelText('Project'), 'PRJ-142');

    for (const { tab, sor, mine, other } of TABS) {
      await user.click(await screen.findByRole('tab', { name: tab }));
      const panel = await screen.findByRole('tabpanel');
      await waitFor(() => expect(panel).toHaveTextContent(mine));
      expect(panel).toHaveTextContent(`System of record: ${sor}`);
      expect(panel).not.toHaveTextContent(other);
    }
  });

  it('BPA77_unassignedProjectIsRefused', async () => {
    // the list was read while PRJ-142 was still assigned; the backend now refuses it
    await openProjects(['project-engineer'], ['PRJ-139', 'PRJ-142'], ['PRJ-142']);
    const user = userEvent.setup();

    await user.selectOptions(await screen.findByLabelText('Project'), 'PRJ-142');

    expect(await screen.findByRole('alert')).toHaveTextContent('Project PRJ-142 is not assigned to you');
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryByText(/System of record/)).not.toBeInTheDocument();
  });

  it('BPA77_userWithNoProjectsSeesEmptyState', async () => {
    await openProjects(['supervisor'], []);

    expect(await screen.findByText('No projects assigned. Ask the PPC Team.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Project')).not.toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  // --- not scenarios (plan BPA-77) -----------------------------------------------------------------------------------------

  it('theFirstProjectIsOpenedWithItsActivities', async () => {
    const { calls } = await openProjects(['time-method-analyst'], ['PRJ-139', 'PRJ-142']);

    const panel = await screen.findByRole('tabpanel');
    await waitFor(() => expect(panel).toHaveTextContent('A-2201'));
    expect(screen.getByRole('tab', { name: 'Activities' })).toHaveAttribute('aria-selected', 'true');
    expect(calls).toContain('GET /api/v1/projects/PRJ-139/master-data');
    expect(calls).not.toContain('GET /api/v1/projects/PRJ-142/master-data');
  });

  it('aProjectWithNoRowsInATabSaysSo', async () => {
    const original = DATA['PRJ-139'];
    DATA['PRJ-139'] = { ...original, costStructure: { ...original.costStructure, rows: [] } }; // before the page asks for it
    try {
      const { user } = await openProjects(['cost-controller'], ['PRJ-139']);

      await user.click(await screen.findByRole('tab', { name: 'Cost structure' }));

      const panel = await screen.findByRole('tabpanel');
      await waitFor(() => expect(panel).toHaveTextContent('No rows'));
      expect(panel).toHaveTextContent('System of record: D365 F&O Cost Control Report');
    } finally {
      DATA['PRJ-139'] = original;
    }
  });
});
