import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ApproveCompletion } from './ApproveCompletion';

// BPA-60: the Approve completion % screen (UserTask_ApproveCompletion). One row per Work Pack with the reported, last locked and hours-based %, the % reported
// by production and the FMS actual hours beside the POC approved hours (information only); PPC changes an approved % (a remark is then required) and approves
// the whole week. The rules are the backend's (CompletionApprovalApiTest); the texts not in Jira are ASSUMPTIONs (plan Q11).
const TASK = '2251799813698000';
const ROWS = [
  {
    workpackNumber: 'WP-000457',
    noReportRow: false,
    reportedPct: 40,
    lastLockedPct: 30,
    hoursBasedPct: 30,
    reportedByProductionPct: 40,
    fmsActualHours: 150,
    approvedHours: 142.5,
    hoursDifference: 7.5,
    approvedPct: 40,
  },
  {
    workpackNumber: 'WP-000461',
    noReportRow: false,
    reportedPct: 100,
    lastLockedPct: 60,
    approvedHours: 20,
    approvedPct: 100,
  },
  { workpackNumber: 'WP-000470', noReportRow: true, lastLockedPct: 0, approvedHours: 0, approvedPct: 0 },
];

type Call = { method: string; path: string; body: unknown };
type Options = {
  completeStatus?: number;
  completeDetail?: string;
  weekStatus?: number;
  rows?: unknown[];
  variables?: Record<string, unknown>;
};

function fakeBackend({
  completeStatus = 204,
  completeDetail = '',
  weekStatus = 200,
  rows = ROWS,
  variables = { weekEnding: '2026-11-19' },
}: Options = {}) {
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
        name: 'Approve completion %',
        elementId: 'UserTask_ApproveCompletion',
        processInstanceKey: '2251799813692700',
        creationDate: '2026-11-20T07:00:00Z',
        variables,
        validationMessages: [],
      });
    }
    if (path === '/api/v1/progress-periods/2026-11-19/workpacks') {
      return weekStatus === 200
        ? json(200, { weekEnding: '2026-11-19', basis: 'Physical', workpacks: rows })
        : json(weekStatus, { detail: 'Week not found' });
    }
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
  render(<ApproveCompletion api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('Week ending 19 Nov 2026');
  if (options.weekStatus === undefined || options.weekStatus === 200) {
    await screen.findByText('WP-000457');
  }
  return { ...backend, user, onDone };
}

const rowOf = (workpack: string) => screen.getByText(workpack).closest('tr') as HTMLElement;

describe('ApproveCompletion', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA60_theGridShowsEveryWorkPackWithItsInformationColumns', async () => {
    await renderScreen();

    const first = rowOf('WP-000457');
    expect(within(first).getByText('Reported by production: 40.00%')).toBeInTheDocument();
    expect(within(first).getByText('150.00')).toBeInTheDocument();
    expect(within(first).getByText('142.50')).toBeInTheDocument();
    expect(within(first).getByText('7.50')).toBeInTheDocument();
    expect(within(first).getByLabelText('Approved % for WP-000457')).toHaveValue('40');
    expect(within(rowOf('WP-000470')).getByText('No report row')).toBeInTheDocument();
    expect(within(rowOf('WP-000470')).getByLabelText('Approved % for WP-000470')).toHaveValue('0');
  });

  it('BPA60_approvingWithoutChangesSendsEveryWorkPack', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body).toEqual({
      variables: {
        completionApprovals: [
          { workpackNumber: 'WP-000457', approvedPct: 40 },
          { workpackNumber: 'WP-000461', approvedPct: 100 },
          { workpackNumber: 'WP-000470', approvedPct: 0 },
        ],
      },
    });
  });

  it('BPA60_aChangedPercentNeedsARemarkBeforeTheCallIsMade', async () => {
    const { user, calls, onDone } = await renderScreen();
    const input = screen.getByLabelText('Approved % for WP-000457');

    await user.clear(input);
    await user.type(input, '38');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('A remark is required when the approved % differs from the reported %')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText('Remark for WP-000457'), 'FMS double-counted cutting');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    const sent = calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body as {
      variables: { completionApprovals: Array<Record<string, unknown>> };
    };
    expect(sent.variables.completionApprovals[0]).toEqual({
      workpackNumber: 'WP-000457',
      approvedPct: 38,
      remark: 'FMS double-counted cutting',
    });
  });

  it('BPA60_aPercentWithThreeDecimalsIsRefusedOnTheScreen', async () => {
    const { user, calls } = await renderScreen();
    const input = screen.getByLabelText('Approved % for WP-000457');

    await user.clear(input);
    await user.type(input, '38.125');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Completion % can have at most 2 decimal places')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
  });

  it('BPA60_theServersRefusalIsShown', async () => {
    const detail = 'Week ending 12 Nov 2026 must be locked first';
    const { user, onDone } = await renderScreen({ completeStatus: 409, completeDetail: detail });

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText(detail)).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});

// QA review of BPA-60: a week that cannot be loaded must not look like "no approvals".
describe('ApproveCompletion, a week that cannot be loaded', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA60_aWeekThatCannotBeLoadedBlocksCompletion', async () => {
    const { user, calls, onDone } = await renderScreen({ weekStatus: 404 });

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('The week could not be loaded')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });
});

// BPA-63: a retraction reason where the approved % is below last week's locked %, and the Project Manager's decline shown back to PPC (plan Q5: a field on the
// read model, retractionDeclinedReason). The texts are Jira's; the backend checks them again (RetractionApiTest).
describe('ApproveCompletion, retractions', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA63_decreaseWithoutReasonIsRefused', async () => {
    const { user, calls, onDone } = await renderScreen();
    const input = screen.getByLabelText('Approved % for WP-000457');
    expect(screen.queryByLabelText('Retraction reason for WP-000457')).not.toBeInTheDocument();

    await user.clear(input);
    await user.type(input, '25');
    await user.type(screen.getByLabelText('Remark for WP-000457'), 'FMS double-counted cutting');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(
      await screen.findByText('A retraction reason is required when completion falls below last week (30%)'),
    ).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText('Retraction reason for WP-000457'), 'FMS double-counted cutting');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    const sent = calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body as {
      variables: { completionApprovals: Array<Record<string, unknown>> };
    };
    expect(sent.variables.completionApprovals[0]).toEqual({
      workpackNumber: 'WP-000457',
      approvedPct: 25,
      remark: 'FMS double-counted cutting',
      retractionReason: 'FMS double-counted cutting',
    });
    expect(sent.variables.completionApprovals[1]).toEqual({ workpackNumber: 'WP-000461', approvedPct: 100 });
  });

  it('BPA63_retractionDeclinedGoesBackToPpc', async () => {
    const declined = {
      ...ROWS[0],
      approvedPct: 25,
      remark: 'FMS double-counted cutting',
      retractionDeclinedReason: 'Recheck with FMS first',
    };
    await renderScreen({ rows: [declined, ROWS[1], ROWS[2]] });

    expect(within(rowOf('WP-000457')).getByText('Retraction declined: Recheck with FMS first')).toBeInTheDocument();
    expect(within(rowOf('WP-000461')).queryByText(/Retraction declined/)).not.toBeInTheDocument();
  });
});

// QA review of BPA-63: where an Approved retraction already holds for the stored %, no reason is asked for (the server decides the same). Written after the code.
describe('ApproveCompletion, an approved retraction', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA63_anApprovedRetractionNeedsNoNewReason', async () => {
    const approved = { ...ROWS[0], approvedPct: 25, remark: 'FMS double-counted cutting', retractionApproved: true };
    const { user, calls, onDone } = await renderScreen({ rows: [approved, ROWS[1], ROWS[2]] });

    expect(screen.queryByLabelText('Retraction reason for WP-000457')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    const sent = calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body as {
      variables: { completionApprovals: Array<Record<string, unknown>> };
    };
    expect(sent.variables.completionApprovals[0]).toEqual({
      workpackNumber: 'WP-000457',
      approvedPct: 25,
      remark: 'FMS double-counted cutting',
    });
  });
});

// BPA-66: when the Project Manager sends the week back to PPC (target Progress), the Approve completion screen shows "Week sent back: <reason>" (the story's text).
// The notice is shown only for a Progress send-back: the variables are on the task's read allow-list. Written before the code.
describe('ApproveCompletion, a week sent back', () => {
  const REASON = 'WP-000461 cannot be 100%: punch list open';

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA66_changesRequestedGoBackToPpc', async () => {
    await renderScreen({
      variables: {
        weekEnding: '2026-11-19',
        weekDecision: 'ChangesRequested',
        weekChangeTarget: 'Progress',
        weekDecisionReason: REASON,
      },
    });

    expect(await screen.findByText(`Week sent back: ${REASON}`)).toBeInTheDocument();
  });

  it('noNoticeForAWeekThatWasNotSentBack', async () => {
    await renderScreen();

    expect(screen.queryByText(/Week sent back/)).not.toBeInTheDocument();
  });

  it('noNoticeForASendBackToCostReview', async () => {
    await renderScreen({
      variables: {
        weekEnding: '2026-11-19',
        weekDecision: 'ChangesRequested',
        weekChangeTarget: 'CostReview',
        weekDecisionReason: 'Explain the spool rework cost',
      },
    });

    expect(screen.queryByText(/Week sent back/)).not.toBeInTheDocument();
  });
});
