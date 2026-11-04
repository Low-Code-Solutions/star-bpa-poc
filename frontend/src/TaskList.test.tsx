import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { apiClient } from './api/client';
import { setRetryMs, TaskList } from './TaskList';

// BPA-6 portal flow (review finding): the Analyst starts a request, completes the Create Work Pack screen, the save step
// rejects it and the task comes back under a new key; the portal reopens it with the messages and the data entered. Then the
// corrected data is saved. The backend is a fetch stub that behaves like WorkPackApiTest shows the real one does.
const INSTANCE = '2251799813690086';
const FIRST = '2251799813690093';
const RETURNED = '2251799813690118';

const ENTERED = {
  activityRef: 'A-2201',
  drawingRef: 'DWG-118',
  departmentRef: 'Fabrication',
  completionDate: '2026-11-30',
  phases: [
    { phase: 'Cutting', manhours: 150 },
    { phase: 'Cutting', manhours: 50 },
  ],
  materials: [{ materialRef: 'MAT-PIPE-06', phase: 'Cutting' }],
};

function fakeBackend() {
  let open: string[] = [];
  const completed: Array<{ key: string; variables: Record<string, unknown> }> = [];
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  const summary = (key: string) => ({
    key,
    name: 'Create Work Pack',
    elementId: 'UserTask_CreateWorkPack',
    processInstanceKey: INSTANCE,
    creationDate: '2026-10-21T06:00:00Z',
  });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    if (path === '/api/v1/workpacks' && input.method === 'POST') {
      open = [FIRST];
      return json(201, { processInstanceKey: INSTANCE });
    }
    if (path === '/api/v1/tasks') {
      return json(200, open.map(summary));
    }
    if (path === `/api/v1/tasks/${FIRST}`) {
      return json(200, { ...summary(FIRST), variables: {}, validationMessages: [] });
    }
    if (path === `/api/v1/tasks/${RETURNED}`) {
      return json(200, { ...summary(RETURNED), variables: ENTERED, validationMessages: ['Phase Cutting is listed twice'] });
    }
    const complete = path.match(/^\/api\/v1\/tasks\/(\d+)\/complete$/);
    if (complete) {
      const body = JSON.parse(await input.clone().text());
      completed.push({ key: complete[1], variables: body.variables });
      // The first completion is rejected by the save step: the task comes back under a new key. The second is saved.
      open = complete[1] === FIRST ? [RETURNED] : [];
      return new Response(null, { status: 204 });
    }
    switch (path) {
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
        return json(200, [{ materialRef: 'MAT-PIPE-06', description: 'Synthetic pipe 6 inch' }]);
      case '/api/v1/departments':
        return json(200, [{ departmentRef: 'Fabrication', name: 'Synthetic Fabrication Department' }]);
      default:
        return json(404, {});
    }
  });
  return { fetchMock, completed };
}

describe('TaskList', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
    setRetryMs(10);
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-21T06:00:00Z'));
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    setRetryMs(500);
  });

  it('reopens a task the save step sent back, with its messages and the data entered', async () => {
    const { fetchMock, completed } = fakeBackend();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<TaskList api={apiClient()} groups={['time-method-analyst']} />);

    await user.click(await screen.findByRole('button', { name: 'Start Work Pack request' }));
    await screen.findByRole('option', { name: /Fabrication/ });
    await user.selectOptions(screen.getByLabelText('Project activity'), 'A-2201');
    await user.selectOptions(screen.getByLabelText('Drawing'), 'DWG-118');
    await user.selectOptions(screen.getByLabelText('Department'), 'Fabrication');
    fireEvent.change(screen.getByLabelText('Date to complete'), { target: { value: '2026-11-30' } });
    for (const hours of ['150', '50']) {
      await user.click(screen.getByRole('button', { name: 'Add phase' }));
      const n = screen.getAllByLabelText(/^Phase \d+$/).length;
      await user.selectOptions(screen.getByLabelText(`Phase ${n}`), 'Cutting');
      await user.type(screen.getByLabelText(`Man hours ${n}`), hours);
    }
    await user.click(screen.getByRole('button', { name: 'Add material' }));
    await user.selectOptions(screen.getByLabelText('Material 1'), 'MAT-PIPE-06');
    await user.selectOptions(screen.getByLabelText('Material 1 phase'), 'Cutting');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    // The returned task opens by itself, showing the save step's message and what was entered.
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Phase Cutting is listed twice')).toBeInTheDocument();
    expect(screen.getByLabelText('Project activity')).toHaveValue('A-2201');
    expect(screen.getByLabelText('Date to complete')).toHaveValue('2026-11-30');
    expect(screen.getByLabelText('Man hours 2')).toHaveValue(50);
    expect(completed.map((c) => c.key)).toEqual([FIRST]);

    // Correct the second phase to Welding and its material line; the save goes to the returned task's key.
    await user.selectOptions(screen.getByLabelText('Phase 2'), 'Welding');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Task completed; the Work Pack is saved')).toBeInTheDocument();
    expect(completed.map((c) => c.key)).toEqual([FIRST, RETURNED]);
    expect(completed[1].variables.phases).toEqual([
      { phase: 'Cutting', manhours: 150 },
      { phase: 'Welding', manhours: 50 },
    ]);
  });
});
