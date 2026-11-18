import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ApproveHoursPpc } from './ApproveHoursPpc';

// BPA-57: the Approve hours (PPC) screen (UserTask_ApproveHoursPpc). It is BPA-56's screen with another title; the rules are the backend's
// (HoursPpcApprovalApiTest). Background: WP-000457, round 1, two lines sent by supervisor, validated by timekeeper, approved by production-supervisor.
const TASK = '2251799813695000';
const SUBMISSION = '88888888-8888-8888-8888-888888888888';

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
          name: 'Approve hours (PPC)',
          elementId: 'UserTask_ApproveHoursPpc',
          processInstanceKey: '2251799813692400',
          workpackNumber: 'WP-000457',
          creationDate: '2026-11-18T08:00:00Z',
          variables: { hoursSubmissionId: SUBMISSION },
          validationMessages: [],
        });
      case `/api/v1/hours-submissions/${SUBMISSION}`:
        return json(200, {
          id: SUBMISSION,
          workpackNumber: 'WP-000457',
          roundNo: 1,
          stage: 'Ppc',
          submittedBy: 'supervisor',
          submittedAt: '2026-11-18T06:00:00Z',
          lines: [
            line('99999999-9999-9999-9999-999999999991', 'E1021', 8),
            line('99999999-9999-9999-9999-999999999992', 'E1099', 7),
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
  render(<ApproveHoursPpc api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('E1021'); // the production-approved lines have loaded
  return { ...backend, user, onDone };
}

describe('ApproveHoursPpc', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA57_screenShowsTheApprovedLinesReadOnlyUnderThePpcTitle', async () => {
    await renderScreen();

    expect(screen.getByRole('form', { name: 'Approve hours (PPC)' })).toBeInTheDocument();
    expect(screen.getByText('E1099')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Amend/ })).not.toBeInTheDocument();
  });

  it('BPA57_approvingCompletesTheTask', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body).toEqual({ variables: { hoursDecision: 'Approved' } });
  });

  it('BPA57_rejectNeedsAReasonBeforeTheCallIsMade', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Reject' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Reason is required')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('BPA57_theEarlierDeciderRefusalIsShown', async () => {
    const detail = 'You already decided these hours at an earlier step';
    const { user, onDone } = await renderScreen({ completeStatus: 409, completeDetail: detail });

    await user.click(screen.getByRole('radio', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText(detail)).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
