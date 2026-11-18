import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ApproveHoursProduction } from './ApproveHoursProduction';

// BPA-56: the Approve hours (Production) screen (UserTask_ApproveHoursProduction; skill new-task-screen). One React test per screen condition. The rules are
// the backend's (HoursProductionApprovalApiTest); the screen shows the validated lines read-only, blocks Return or Reject without a reason and shows the
// server's message. Background: WP-000457, round 1, two lines sent by supervisor and validated by timekeeper.
const TASK = '2251799813694000';
const SUBMISSION = '66666666-6666-6666-6666-666666666666';

type Call = { method: string; path: string; body: unknown };
type Options = { completeStatus?: number; completeDetail?: string };

const line = (id: string, badgeNo: string, hours: number) => ({
  id,
  badgeNo,
  workDate: '2026-11-16',
  phase: 'Cutting',
  hours,
  status: 'Submitted',
});

function fakeBackend({ completeStatus = 204, completeDetail = '' }: Options = {}) {
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
          name: 'Approve hours (Production)',
          elementId: 'UserTask_ApproveHoursProduction',
          processInstanceKey: '2251799813692400',
          workpackNumber: 'WP-000457',
          creationDate: '2026-11-18T07:00:00Z',
          variables: { hoursSubmissionId: SUBMISSION },
          validationMessages: [],
        });
      case `/api/v1/hours-submissions/${SUBMISSION}`:
        return json(200, {
          id: SUBMISSION,
          workpackNumber: 'WP-000457',
          roundNo: 1,
          stage: 'ProductionSupervisor',
          submittedBy: 'supervisor',
          submittedAt: '2026-11-18T06:00:00Z',
          lines: [
            line('77777777-7777-7777-7777-777777777771', 'E1021', 8),
            line('77777777-7777-7777-7777-777777777772', 'E1099', 7),
          ],
        });
      case `/api/v1/tasks/${TASK}/complete`:
        return completeStatus === 204 ? new Response(null, { status: 204 }) : json(completeStatus, { detail: completeDetail });
      default:
        return json(404, {});
    }
  });
  return { fetchMock, calls };
}

async function renderScreen(options: Options = {}) {
  const backend = fakeBackend(options);
  vi.stubGlobal('fetch', backend.fetchMock);
  const user = userEvent.setup();
  const onDone = vi.fn();
  render(<ApproveHoursProduction api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('E1021'); // the validated lines have loaded
  return { ...backend, user, onDone };
}

const completion = (calls: Call[]) => calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body;

describe('ApproveHoursProduction', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA56_screenShowsTheValidatedLinesReadOnly', async () => {
    await renderScreen();

    const table = screen.getByRole('table', { name: 'Hours sent' });
    expect(within(table).getByText('E1099')).toBeInTheDocument();
    expect(within(table).getByText('7')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Amend/ })).not.toBeInTheDocument();
    expect(screen.getByDisplayValue('WP-000457')).toBeInTheDocument();
  });

  it('BPA56_approvingCompletesTheTask', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(completion(calls)).toEqual({ variables: { hoursDecision: 'Approved' } });
  });

  it('BPA56_returningSendsTheReason', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Return to Supervisor' }));
    await user.type(screen.getByLabelText('Reason'), 'E1099 was on Welding');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(completion(calls)).toEqual({ variables: { hoursDecision: 'Returned', hoursReason: 'E1099 was on Welding' } });
  });

  it('BPA56_rejectingSendsTheReason', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Reject' }));
    await user.type(screen.getByLabelText('Reason'), 'Hours not worked on this Work Pack');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(completion(calls)).toEqual({
      variables: { hoursDecision: 'Rejected', hoursReason: 'Hours not worked on this Work Pack' },
    });
  });

  it.each(['Return to Supervisor', 'Reject'])('BPA56_returnOrRejectNeedsAReason (%s)', async (choice) => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: choice }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Reason is required')).toBeInTheDocument();
    expect(completion(calls)).toBeUndefined();
    expect(onDone).not.toHaveBeenCalled();
  });

  it('BPA56_noDecisionIsBlocked', async () => {
    const { user, calls } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Decision is required')).toBeInTheDocument();
    expect(completion(calls)).toBeUndefined();
  });

  it('BPA56_theServersRefusalIsShown', async () => {
    const detail = 'Segregation of duties: production-supervisor sent these hours and cannot decide on them';
    const { user, onDone } = await renderScreen({ completeStatus: 403, completeDetail: detail });

    await user.click(screen.getByRole('radio', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText(detail)).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
