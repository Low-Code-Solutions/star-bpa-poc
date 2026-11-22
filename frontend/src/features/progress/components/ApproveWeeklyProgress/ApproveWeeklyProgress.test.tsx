import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ApproveWeeklyProgress } from './ApproveWeeklyProgress';

// BPA-66: the Approve weekly progress screen (UserTask_ApproveWeeklyProgress, group project-manager). Per Work Pack the approved % and last week's %; per activity the
// Earned man-hours; the week's man-hour and cost alerts with the Cost Controller's remarks; for a Work Pack approved at 100% its unsent (Draft, Returned) lines, information
// only. The Project Manager approves the week or requests changes (target Progress or Cost review, reason required). The rules are the backend's (WeekApprovalApiTest);
// the widget texts are ASSUMPTIONs of the plan except "Reason is required" and the alert and remark texts of the story.
const TASK = '2251799813699900';
const WORKPACKS = {
  weekEnding: '2026-11-19',
  basis: 'Physical',
  workpacks: [
    { workpackNumber: 'WP-000457', noReportRow: false, lastLockedPct: 30, approvedHours: 142.5, approvedPct: 40 },
    {
      workpackNumber: 'WP-000461',
      noReportRow: false,
      lastLockedPct: 60,
      approvedHours: 20,
      approvedPct: 100,
      unsentLines: [
        { employeeRef: 'E1021', workDate: '2026-11-16', phase: 'Cutting', hours: 8, status: 'Draft' },
        { employeeRef: 'E1099', workDate: '2026-11-17', phase: 'Welding', hours: 6, status: 'Returned' },
      ],
    },
  ],
};
const EARNED = {
  weekEnding: '2026-11-19',
  activities: [
    {
      activityRef: 'A-2201',
      activityName: 'Fabricate spools',
      actualPct: 50,
      budgetedUnits: 1200,
      earnedManhours: 600,
      lastLockedEarnedManhours: 360,
      weekIncrease: 240,
    },
  ],
};
const CBS = {
  weekEnding: '2026-11-19',
  noCbsManhours: 0,
  cbs: [
    {
      cbsCodeRef: 'CBS-139-PIP-01',
      actualManhours: 1200,
      currentForecastManhours: 1000,
      etc: -200,
      variancePct: 20,
      result: 'OVER_15_PCT',
      alert: true,
      actualCost: 300000,
      revisedBudgetCost: 250000,
      costVariancePct: 20,
      costResult: 'OVER_10_PCT',
      costAlert: true,
      remark: 'Rework on spools',
    },
    { cbsCodeRef: 'CBS-140-STR-01', actualManhours: 50, result: 'NO_FORECAST', alert: false, costAlert: false },
  ],
};

type Call = { method: string; path: string; body: unknown };
type Options = { completeStatus?: number; completeDetail?: string };

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
    if (path === `/api/v1/tasks/${TASK}`) {
      return json(200, {
        key: TASK,
        name: 'Approve weekly progress',
        elementId: 'UserTask_ApproveWeeklyProgress',
        processInstanceKey: '2251799813692900',
        creationDate: '2026-11-20T07:00:00Z',
        variables: { weekEnding: '2026-11-19' },
        validationMessages: [],
      });
    }
    if (path === '/api/v1/progress-periods/2026-11-19/workpacks') return json(200, WORKPACKS);
    if (path === '/api/v1/progress-periods/2026-11-19/earned-manhours') return json(200, EARNED);
    if (path === '/api/v1/progress-periods/2026-11-19/cbs-actuals') return json(200, CBS);
    if (path === `/api/v1/tasks/${TASK}/complete`) {
      return completeStatus === 204 ? new Response(null, { status: 204 }) : json(completeStatus, { detail: completeDetail });
    }
    return json(404, {});
  });
  return { fetchMock, calls };
}

async function renderScreen(options: Options = {}) {
  const backend = fakeBackend(options);
  vi.stubGlobal('fetch', backend.fetchMock);
  const user = userEvent.setup();
  const onDone = vi.fn();
  render(<ApproveWeeklyProgress api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('Week ending 19 Nov 2026');
  await screen.findByText('WP-000457');
  return { ...backend, user, onDone };
}

const rowOf = (text: string) => screen.getByText(text).closest('tr') as HTMLElement;
const completeCall = (calls: Call[]) => calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`);

describe('ApproveWeeklyProgress', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA66_weekShowsAlertsAndRemarks', async () => {
    await renderScreen();

    const row = await screen.findByText('CBS-139-PIP-01').then((cell) => cell.closest('tr') as HTMLElement);
    expect(within(row).getByText('Man-hour alert')).toBeInTheDocument();
    expect(within(row).getByText('Cost alert')).toBeInTheDocument();
    expect(within(row).getByText('Rework on spools')).toBeInTheDocument();
    expect(within(rowOf('WP-000457')).getByText('40.00')).toBeInTheDocument(); // approved %
    expect(within(rowOf('WP-000457')).getByText('30.00')).toBeInTheDocument(); // last week's %
    expect(
      within(await screen.findByText('A-2201').then((cell) => cell.closest('tr') as HTMLElement)).getByText('600.00'),
    ).toBeInTheDocument();
  });

  it('BPA66_changesNeedAReason', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Request changes' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Reason is required')).toBeInTheDocument();
    expect(completeCall(calls)).toBeUndefined();
    expect(onDone).not.toHaveBeenCalled();
  });

  it('approvingSendsExactlyTheWriteList', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Approve week' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(completeCall(calls)?.body).toEqual({ variables: { weekDecision: 'Approved' } });
  });

  it('aSendBackToCostReviewSendsTargetAndReason', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Request changes' }));
    await user.selectOptions(screen.getByLabelText('Send back to'), 'CostReview');
    await user.type(screen.getByLabelText('Reason'), 'Explain the spool rework cost');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(completeCall(calls)?.body).toEqual({
      variables: {
        weekDecision: 'ChangesRequested',
        weekChangeTarget: 'CostReview',
        weekDecisionReason: 'Explain the spool rework cost',
      },
    });
  });

  it('theUnsentLinesOfAWorkPackAt100AreListed', async () => {
    await renderScreen();

    const table = screen.getByRole('table', { name: 'Unsent hours of WP-000461 (approved at 100%)' });
    expect(within(table).getByText('E1021')).toBeInTheDocument();
    expect(within(table).getByText('Draft')).toBeInTheDocument();
    expect(within(table).getByText('E1099')).toBeInTheDocument();
    expect(within(table).getByText('Returned')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: /Unsent hours of WP-000457/ })).not.toBeInTheDocument();
  });

  it('theServersRefusalIsShown', async () => {
    const { user, onDone } = await renderScreen({
      completeStatus: 409,
      completeDetail: 'You cannot approve a week whose progress you approved',
    });

    await user.click(screen.getByRole('radio', { name: 'Approve week' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('You cannot approve a week whose progress you approved')).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
