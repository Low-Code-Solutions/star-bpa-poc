import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ConfirmWorkPack } from './ConfirmWorkPack';

// BPA-23: the Confirm Workpack screen (09 S14; skill new-task-screen). One React test per screen condition; the save step
// re-checks everything (ConfirmWorkPackApiTest). The fake backend answers like the real task API: the Confirm task's variables
// carry the saved phases and material lines with their ids (data model A4).
const TASK = '2251799813690140';

type Call = { method: string; path: string; body: unknown };

function fakeBackend(extra: Record<string, unknown> = {}) {
  const calls: Call[] = [];
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    const body =
      input.method === 'GET'
        ? null
        : await input
            .clone()
            .text()
            .then((t) => (t ? JSON.parse(t) : null));
    calls.push({ method: input.method, path, body });
    switch (path) {
      case `/api/v1/tasks/${TASK}`:
        return json(200, {
          key: TASK,
          name: 'Confirm Workpack',
          elementId: 'UserTask_ConfirmWorkPack',
          processInstanceKey: '2251799813690086',
          workpackNumber: 'WP-000457',
          creationDate: '2026-10-21T06:00:00Z',
          variables: {
            activityRef: 'A-2201',
            drawingRef: 'DWG-118',
            departmentRef: 'Fabrication',
            completionDate: '2026-11-06',
            phases: [
              { phase: 'Cutting', manhours: 150 },
              { phase: 'Welding', manhours: 250 },
            ],
            materials: [{ id: 'line-pipe', materialRef: 'MAT-PIPE-06', phase: 'Cutting' }],
          },
          validationMessages: [],
          ...extra,
        });
      case '/api/v1/activities':
        return json(200, [
          {
            activityRef: 'A-2201',
            activityName: 'Synthetic piping module M-01',
            wbsRef: '11031',
            projectRef: 'PRJ-139',
            jobCodeRef: 'JC-139',
          },
        ]);
      case '/api/v1/drawings':
        return json(200, [{ drawingRef: 'DWG-118', title: 'Synthetic drawing 118' }]);
      case '/api/v1/materials':
        return json(200, [
          { materialRef: 'MAT-PIPE-06', description: 'Synthetic pipe 6 inch' },
          { materialRef: 'MAT-FLG-02', description: 'Synthetic flange 2 inch' },
          { materialRef: 'MAT-PLATE-12', description: 'Synthetic plate 12 mm' },
        ]);
      case '/api/v1/departments':
        return json(200, [{ departmentRef: 'Fabrication', name: 'Synthetic Fabrication Department' }]);
      case `/api/v1/tasks/${TASK}/complete`:
        return new Response(null, { status: 204 });
      default:
        return json(404, {});
    }
  });
  const completions = () => calls.filter((c) => c.path === `/api/v1/tasks/${TASK}/complete`);
  return { fetchMock, completions };
}

async function renderScreen(extra: Record<string, unknown> = {}) {
  const backend = fakeBackend(extra);
  vi.stubGlobal('fetch', backend.fetchMock);
  const onDone = vi.fn();
  const user = userEvent.setup();
  render(<ConfirmWorkPack api={apiClient()} taskKey={TASK} onDone={onDone} />);
  // Prefilled from the task: the saved lines are there once the task and the lists have loaded.
  await screen.findByDisplayValue('250');
  await screen.findByRole('option', { name: /MAT-PLATE-12/ });
  return { ...backend, user, onDone };
}

type Sent = {
  variables: {
    phases: Array<{ phase: string; manhours: number }>;
    materials: Array<{ id?: string; materialRef: string; phase: string }>;
  };
};

function shownMessages(): string[] {
  const alert = screen.queryByRole('alert');
  return alert
    ? within(alert)
        .queryAllByRole('listitem')
        .map((li) => li.textContent ?? '')
    : [];
}

describe('ConfirmWorkPack', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-21T06:00:00Z')); // Background: today is 21 Oct 2026 (Asia/Riyadh)
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('BPA23_correctManHoursRecalculatesHeader', async () => {
    const { user, completions, onDone } = await renderScreen();

    const welding = screen.getByLabelText('Man hours 2');
    await user.clear(welding);
    await user.type(welding, '300');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(completions()).toHaveLength(1);
    const sent = completions()[0].body as Sent;
    expect(sent.variables.phases).toEqual([
      { phase: 'Cutting', manhours: 150 },
      { phase: 'Welding', manhours: 300 },
    ]);
    // The saved line goes back with its id, so the save step updates it in place (A4, F13).
    expect(sent.variables.materials).toEqual([{ id: 'line-pipe', materialRef: 'MAT-PIPE-06', phase: 'Cutting' }]);
    expect(onDone).toHaveBeenCalled();
  });

  it('BPA23_correctMaterialsAndPhases', async () => {
    const { user, completions } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Add material' }));
    await user.selectOptions(screen.getByLabelText('Material 2'), 'MAT-PLATE-12');
    await user.selectOptions(screen.getByLabelText('Material 2 phase'), 'Cutting');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    const sent = completions()[0].body as Sent;
    expect(sent.variables.materials).toEqual([
      { id: 'line-pipe', materialRef: 'MAT-PIPE-06', phase: 'Cutting' },
      { materialRef: 'MAT-PLATE-12', phase: 'Cutting' },
    ]);
  });

  it('BPA23_invalidCorrectionIsRejected', async () => {
    // Row "change Cutting to 0 man hours": the screen blocks submission (field rule). The two cross-field rows are the save
    // step's, tested in ConfirmWorkPackApiTest.
    const { user, completions } = await renderScreen();

    const cutting = screen.getByLabelText('Man hours 1');
    await user.clear(cutting);
    await user.type(cutting, '0');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(shownMessages()).toContain('Man hours must be greater than 0');
    expect(completions()).toHaveLength(0);
  });

  it('shows the Workpack number read-only and the latest return reason (D51)', async () => {
    await renderScreen({ confirmMessage: 'Materials are not reserved for this Workpack' });

    const number = screen.getByLabelText('Workpack number');
    expect(number).toHaveValue('WP-000457');
    expect(number).toHaveAttribute('readonly');
    expect(
      within(screen.getByRole('region', { name: 'Return reason' })).getByText('Materials are not reserved for this Workpack'),
    ).toBeInTheDocument();
  });

  // BPA-8: the release check's reason comes back on this same screen, with no new screen. D51 was built with BPA-23, so this
  // test is expected to pass already (the plan says so; the failing-first tests for BPA-8 are the backend ones).
  it('BPA8_materialNotReservedBlocksConfirmation', async () => {
    await renderScreen({ confirmMessage: 'Drawing is not approved (IFC or As-Built required)' });

    expect(
      within(screen.getByRole('region', { name: 'Return reason' })).getByText('Drawing is not approved (IFC or As-Built required)'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Complete task' })).toBeEnabled();
  });

  it('shows no return reason when the task has none (a first visit, or after Ready)', async () => {
    await renderScreen();

    expect(screen.queryByRole('region', { name: 'Return reason' })).not.toBeInTheDocument();
  });
});
