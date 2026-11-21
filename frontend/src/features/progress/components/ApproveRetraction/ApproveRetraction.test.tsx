import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ApproveRetraction } from './ApproveRetraction';

// BPA-63: the Approve progress retraction screen (UserTask_ApproveRetraction, group project-manager). One task lists every Work Pack that waits for a retraction
// with its last locked %, new % and PPC's reason; the Project Manager decides each one (Approved, or Declined with a reason). The rules are the backend's
// (RetractionApiTest); "Reason is required" is Jira's text, the other texts are ASSUMPTIONs (plan Q7).
const TASK = '2251799813699000';
const ROWS = [
  {
    workpackNumber: 'WP-000457',
    noReportRow: false,
    reportedPct: 40,
    lastLockedPct: 30,
    approvedHours: 142.5,
    approvedPct: 25,
    remark: 'Changed by PPC',
    retractionReason: 'FMS double-counted cutting',
    retractionRequestedBy: 'ppc-team',
  },
  {
    workpackNumber: 'WP-000461',
    noReportRow: false,
    reportedPct: 100,
    lastLockedPct: 60,
    approvedHours: 20,
    approvedPct: 55,
    remark: 'Changed by PPC',
    retractionReason: 'Recount',
    retractionRequestedBy: 'ppc-team',
  },
  { workpackNumber: 'WP-000470', noReportRow: true, lastLockedPct: 0, approvedHours: 0, approvedPct: 0 },
];

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
        name: 'Approve progress retraction',
        elementId: 'UserTask_ApproveRetraction',
        processInstanceKey: '2251799813692700',
        creationDate: '2026-11-20T07:00:00Z',
        variables: { weekEnding: '2026-11-19' },
        validationMessages: [],
      });
    }
    if (path === '/api/v1/progress-periods/2026-11-19/workpacks') {
      return json(200, { weekEnding: '2026-11-19', basis: 'Physical', workpacks: ROWS });
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
  render(<ApproveRetraction api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('Week ending 19 Nov 2026');
  await screen.findByText('WP-000457');
  return { ...backend, user, onDone };
}

const rowOf = (workpack: string) => screen.getByText(workpack).closest('tr') as HTMLElement;

describe('ApproveRetraction', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA63_pmDecidesEachWorkPack', async () => {
    const { user, calls, onDone } = await renderScreen();

    const first = rowOf('WP-000457');
    expect(within(first).getByText('30.00')).toBeInTheDocument();
    expect(within(first).getByText('25.00')).toBeInTheDocument();
    expect(within(first).getByText('FMS double-counted cutting')).toBeInTheDocument();
    expect(within(rowOf('WP-000461')).getByText('Recount')).toBeInTheDocument();
    expect(screen.queryByText('WP-000470')).not.toBeInTheDocument(); // no retraction waiting for it

    await user.click(screen.getByLabelText('Approved for WP-000457'));
    await user.click(screen.getByLabelText('Declined for WP-000461'));
    await user.type(screen.getByLabelText('Reason for WP-000461'), 'Keep 60% until QC');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body).toEqual({
      variables: {
        retractionDecisions: [
          { workpackNumber: 'WP-000457', retractionDecision: 'Approved' },
          { workpackNumber: 'WP-000461', retractionDecision: 'Declined', reason: 'Keep 60% until QC' },
        ],
      },
    });
  });

  it('BPA63_declineNeedsAReason', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByLabelText('Approved for WP-000457'));
    await user.click(screen.getByLabelText('Declined for WP-000461'));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Reason is required')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('BPA63_approverMustNotBeRequester', async () => {
    const detail = 'You cannot approve a retraction you requested';
    const { user, onDone } = await renderScreen({ completeStatus: 409, completeDetail: detail });

    await user.click(screen.getByLabelText('Approved for WP-000457'));
    await user.click(screen.getByLabelText('Approved for WP-000461'));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText(detail)).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
