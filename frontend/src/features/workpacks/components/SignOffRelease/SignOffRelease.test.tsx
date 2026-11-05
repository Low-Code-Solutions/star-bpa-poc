import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { SignOffRelease } from './SignOffRelease';

// BPA-9: the Sign off release task screen (09 S14; skill new-task-screen). One React test per screen condition; the backend
// re-checks the decline reason before completing the task (SignOffApiTest, TaskValidityTest). The fake backend answers like the
// real task API: the sign-off task's variables carry what the Project Engineer signs (the read allow-list).
const TASK = '2251799813691000';

type Call = { method: string; path: string; body: unknown };

function fakeBackend() {
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
          name: 'Sign off release',
          elementId: 'UserTask_SignOffRelease',
          processInstanceKey: '2251799813690986',
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
            materials: [
              { id: 'line-pipe', materialRef: 'MAT-PIPE-06', phase: 'Cutting' },
              { id: 'line-flange', materialRef: 'MAT-FLG-02', phase: 'Welding' },
            ],
          },
          validationMessages: [],
        });
      case `/api/v1/tasks/${TASK}/complete`:
        return new Response(null, { status: 204 });
      default:
        return json(404, {});
    }
  });
  const completions = () => calls.filter((c) => c.path === `/api/v1/tasks/${TASK}/complete`);
  return { fetchMock, completions };
}

async function renderScreen() {
  const backend = fakeBackend();
  vi.stubGlobal('fetch', backend.fetchMock);
  const onDone = vi.fn();
  const user = userEvent.setup();
  render(<SignOffRelease api={apiClient()} taskKey={TASK} onDone={onDone} />);
  // The task has loaded once its Workpack number is shown (prefilled from the task).
  await screen.findByDisplayValue('WP-000457');
  return { ...backend, user, onDone };
}

function shownMessages(): string[] {
  const alert = screen.queryByRole('alert');
  return alert
    ? within(alert)
        .queryAllByRole('listitem')
        .map((li) => li.textContent ?? '')
    : [];
}

describe('SignOffRelease', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA9_releaseIsSignedOff', async () => {
    const { user, completions, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Approve release' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(completions()).toHaveLength(1);
    expect(completions()[0].body).toEqual({ variables: { signOffDecision: 'Approved' } });
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('BPA9_declineReturnsToAnalyst', async () => {
    const { user, completions, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Decline' }));
    await user.type(screen.getByLabelText('Reason for declining'), 'Wrong drawing revision');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(completions()).toHaveLength(1);
    expect(completions()[0].body).toEqual({
      variables: { signOffDecision: 'Declined', declineReason: 'Wrong drawing revision' },
    });
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('BPA9_declineNeedsReason', async () => {
    const { user, completions, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Decline' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(shownMessages()).toContain('Reason is required');
    // A reason of only blanks is no reason either (the backend and the V1 CHECK agree).
    await user.type(screen.getByLabelText('Reason for declining'), '   ');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).toContain('Reason is required');
    expect(completions()).toHaveLength(0);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('shows the Work Pack to sign, read-only (form spec)', async () => {
    await renderScreen();

    expect(screen.getByLabelText('Workpack number')).toHaveValue('WP-000457');
    expect(screen.getByLabelText('Workpack number')).toHaveAttribute('readonly');
    const details = within(screen.getByRole('region', { name: 'Work Pack details' }));
    for (const text of [
      'A-2201',
      'DWG-118',
      'Fabrication',
      '06 Nov 2026',
      'Cutting',
      '150',
      'Welding',
      '250',
      'MAT-PIPE-06',
      'MAT-FLG-02',
    ]) {
      expect(details.getAllByText(text, { exact: false }).length).toBeGreaterThan(0);
    }
  });

  it('blocks a completion with no decision', async () => {
    const { user, completions } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(shownMessages()).toContain('Decision is required');
    expect(completions()).toHaveLength(0);
  });

  it('shows the reason field only when declining, and sends no reason when approving', async () => {
    const { user, completions } = await renderScreen();

    expect(screen.queryByLabelText('Reason for declining')).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Decline' }));
    await user.type(screen.getByLabelText('Reason for declining'), 'Changed my mind');
    await user.click(screen.getByRole('radio', { name: 'Approve release' }));
    expect(screen.queryByLabelText('Reason for declining')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(completions()[0].body).toEqual({ variables: { signOffDecision: 'Approved' } });
  });
});
