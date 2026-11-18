import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ValidateHours } from './ValidateHours';

// BPA-53: the Validate hours screen (UserTask_ValidateHours; skill new-task-screen). One React test per screen condition. The rules are the
// backend's (HoursValidationApiTest); the screen shows the sent lines, blocks a return without a reason, amends a line with a reason and shows the
// server's message. Background: WP-000457, round 1, three lines sent by the supervisor.
const TASK = '2251799813693000';
const SUBMISSION = '44444444-4444-4444-4444-444444444444';
const LINE_1 = '55555555-5555-5555-5555-555555555551';
const LINE_2 = '55555555-5555-5555-5555-555555555552';

type Call = { method: string; path: string; body: unknown };
type Options = { amendStatus?: number; amendDetail?: string; completeStatus?: number; completeDetail?: string };

const line = (id: string, badgeNo: string, workDate: string, hours: number) => ({
  id,
  badgeNo,
  workDate,
  phase: 'Cutting',
  hours,
  status: 'Submitted',
});

function fakeBackend({ amendStatus = 200, amendDetail = '', completeStatus = 204, completeDetail = '' }: Options = {}) {
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
          name: 'Validate hours',
          elementId: 'UserTask_ValidateHours',
          processInstanceKey: '2251799813692400',
          workpackNumber: 'WP-000457',
          creationDate: '2026-11-18T06:00:00Z',
          variables: { hoursSubmissionId: SUBMISSION },
          validationMessages: [],
        });
      case `/api/v1/hours-submissions/${SUBMISSION}`:
        return json(200, {
          id: SUBMISSION,
          workpackNumber: 'WP-000457',
          roundNo: 1,
          stage: 'Timekeeper',
          submittedBy: 'supervisor',
          submittedAt: '2026-11-18T06:00:00Z',
          lines: [line(LINE_1, 'E1021', '2026-11-16', 8), line(LINE_2, 'E1099', '2026-11-16', 7)],
        });
      case `/api/v1/digital-timesheet-lines/${LINE_2}/amendments`:
        return amendStatus === 200
          ? json(200, {
              ...line(LINE_2, 'E1099', '2026-11-16', 7),
              hours: (body as { hours: number }).hours,
              workDate: '2026-11-16',
            })
          : json(amendStatus, { detail: amendDetail });
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
  render(<ValidateHours api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('E1021'); // the sent lines have loaded
  return { ...backend, user, onDone };
}

describe('ValidateHours', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA53_screenShowsTheSentLines', async () => {
    await renderScreen();

    const table = screen.getByRole('table', { name: 'Hours sent' });
    expect(within(table).getByText('E1021')).toBeInTheDocument();
    expect(within(table).getByText('E1099')).toBeInTheDocument();
    expect(within(table).getByText('7')).toBeInTheDocument();
    expect(screen.getByDisplayValue('WP-000457')).toBeInTheDocument();
  });

  it('BPA53_validatingCompletesTheTask', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Validate' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    const completion = calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`);
    expect(completion?.body).toEqual({ variables: { hoursDecision: 'Validated' } });
  });

  it('BPA53_returnNeedsAReasonBeforeTheCallIsMade', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Return to Supervisor' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Reason is required')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('BPA53_noDecisionIsBlocked', async () => {
    const { user, calls } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Decision is required')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
  });

  it('BPA53_returningSendsTheReason', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Return to Supervisor' }));
    await user.type(screen.getByLabelText('Reason for returning'), 'E1099 16 Nov should be Welding');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    const completion = calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`);
    expect(completion?.body).toEqual({ variables: { hoursDecision: 'Returned', hoursReason: 'E1099 16 Nov should be Welding' } });
  });

  it('BPA53_amendingALineSendsTheHoursAndReason', async () => {
    const { user, calls } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Amend E1099 16 Nov 2026 Cutting' }));
    const panel = screen.getByRole('region', { name: 'Amend hours' });
    await user.clear(within(panel).getByLabelText('Hours'));
    await user.type(within(panel).getByLabelText('Hours'), '7.5');
    await user.type(within(panel).getByLabelText('Reason for amending'), 'Supervisor request');
    await user.click(within(panel).getByRole('button', { name: 'Save amendment' }));

    await waitFor(() => expect(calls.some((c) => c.path === `/api/v1/digital-timesheet-lines/${LINE_2}/amendments`)).toBe(true));
    const amendment = calls.find((c) => c.path.endsWith('/amendments'));
    expect(amendment?.body).toEqual({ hours: 7.5, reason: 'Supervisor request' });
  });

  it('BPA53_amendingWithoutAReasonIsBlockedOnTheScreen', async () => {
    const { user, calls } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Amend E1099 16 Nov 2026 Cutting' }));
    const panel = screen.getByRole('region', { name: 'Amend hours' });
    await user.click(within(panel).getByRole('button', { name: 'Save amendment' }));

    expect(await within(panel).findByText('Reason is required')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/amendments'))).toBe(false);
  });

  it('BPA53_theServersAmendmentRefusalIsShown', async () => {
    const { user } = await renderScreen({ amendStatus: 400, amendDetail: 'Phase Cutting budget of 300 h would be exceeded' });

    await user.click(screen.getByRole('button', { name: 'Amend E1099 16 Nov 2026 Cutting' }));
    const panel = screen.getByRole('region', { name: 'Amend hours' });
    await user.clear(within(panel).getByLabelText('Hours'));
    await user.type(within(panel).getByLabelText('Hours'), '8');
    await user.type(within(panel).getByLabelText('Reason for amending'), 'Supervisor request');
    await user.click(within(panel).getByRole('button', { name: 'Save amendment' }));

    expect(await within(panel).findByText('Phase Cutting budget of 300 h would be exceeded')).toBeInTheDocument();
  });

  it('BPA53_theServersCompletionRefusalIsShown', async () => {
    const { user, onDone } = await renderScreen({ completeStatus: 403, completeDetail: 'Not allowed' });

    await user.click(screen.getByRole('radio', { name: 'Validate' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Not allowed')).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
