import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { SendHoursToFms } from './SendHoursToFms';

// BPA-58: the Send hours to FMS screen (UserTask_SendHoursToFms). PPC downloads each file of the week, uploads it to FMS by hand and gives one outcome per
// file; the rules are the backend's (FmsHoursExportApiTest, TaskValidityTest). The labels and texts not in Jira are ASSUMPTIONs (plan Q11).
const TASK = '2251799813696000';
const NOVEMBER = '11111111-1111-1111-1111-111111111111';
const DECEMBER = '22222222-2222-2222-2222-222222222222';

type Call = { method: string; path: string; body: unknown };
type Options = {
  files?: Array<{ id: string; periodMonth: string; rowCount: number }>;
  completeStatus?: number;
  completeDetail?: string;
};

function fakeBackend({
  files = [{ id: NOVEMBER, periodMonth: '2026-11-01', rowCount: 3 }],
  completeStatus = 204,
  completeDetail = '',
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
        name: 'Send hours to FMS',
        elementId: 'UserTask_SendHoursToFms',
        processInstanceKey: '2251799813692500',
        creationDate: '2026-11-19T21:00:00Z',
        variables: { weekEnding: '2026-11-19', fmsHoursExports: files },
        validationMessages: [],
      });
    }
    if (path.startsWith('/api/v1/fms-hours-exports/') && path.endsWith('/file')) {
      return new Response(new Blob(['xlsx']), {
        status: 200,
        headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
      });
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
  URL.createObjectURL = vi.fn(() => 'blob:download-1');
  URL.revokeObjectURL = vi.fn();
  const user = userEvent.setup();
  const onDone = vi.fn();
  render(<SendHoursToFms api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('Week ending 19 Nov 2026');
  return { ...backend, user, onDone };
}

describe('SendHoursToFms', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA58_screenListsEachFileWithItsDownload', async () => {
    const { user, calls } = await renderScreen({
      files: [
        { id: NOVEMBER, periodMonth: '2026-11-01', rowCount: 3 },
        { id: DECEMBER, periodMonth: '2026-12-01', rowCount: 1 },
      ],
    });

    expect(screen.getByRole('form', { name: 'Send hours to FMS' })).toBeInTheDocument();
    const december = screen.getByRole('group', { name: 'December 2026' });
    expect(within(december).getByText('1 row')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: 'November 2026' })).getByText('3 rows')).toBeInTheDocument();

    await user.click(within(december).getByRole('button', { name: 'Download' }));

    await waitFor(() => expect(calls.some((c) => c.path === `/api/v1/fms-hours-exports/${DECEMBER}/file`)).toBe(true));
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalledTimes(1));
  });

  it('BPA58_sentCompletesWithTheOutcomeOfEachFile', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Sent to FMS' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body).toEqual({
      variables: { fmsHandOffs: [{ fmsHoursExportId: NOVEMBER, outcome: 'Sent to FMS' }] },
    });
  });

  it('BPA58_rejectedNeedsAReasonBeforeTheCallIsMade', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('radio', { name: 'Rejected by FMS' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Reason is required')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('BPA58_everyFileNeedsAnOutcomeAndARejectionSendsItsReason', async () => {
    const { user, calls, onDone } = await renderScreen({
      files: [
        { id: NOVEMBER, periodMonth: '2026-11-01', rowCount: 3 },
        { id: DECEMBER, periodMonth: '2026-12-01', rowCount: 1 },
      ],
    });

    await user.click(within(screen.getByRole('group', { name: 'November 2026' })).getByRole('radio', { name: 'Sent to FMS' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(await screen.findByText('Outcome is required for each file')).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();

    const december = screen.getByRole('group', { name: 'December 2026' });
    await user.click(within(december).getByRole('radio', { name: 'Rejected by FMS' }));
    await user.type(within(december).getByRole('textbox', { name: 'Reason' }), 'Badge E1099 unknown in FMS');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body).toEqual({
      variables: {
        fmsHandOffs: [
          { fmsHoursExportId: NOVEMBER, outcome: 'Sent to FMS' },
          { fmsHoursExportId: DECEMBER, outcome: 'Rejected by FMS', reason: 'Badge E1099 unknown in FMS' },
        ],
      },
    });
  });

  it('BPA58_theServersRefusalIsShown', async () => {
    const detail = 'Reason can be at most 250 characters';
    const { user, onDone } = await renderScreen({ completeStatus: 400, completeDetail: detail });

    await user.click(screen.getByRole('radio', { name: 'Sent to FMS' }));
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText(detail)).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
