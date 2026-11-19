import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { UploadCompletionReport } from './UploadCompletionReport';

// BPA-59: the Upload FMS completion report screen (UserTask_UploadCompletionReport). PPC uploads the report into the week's task, sees every row with its
// outcome and completes the task with the report that counts. The row rules are the backend's (CompletionReportApiTest); the texts not in Jira are
// ASSUMPTIONs (plan Q9).
const TASK = '2251799813697000';
const REPORT = '5c6f8a52-9e1c-4d6e-8d52-0a1b2c3d4e5f';
const RESULT = {
  id: REPORT,
  fileName: 'fms-completion-wk47.xlsx',
  receivedAt: '2026-11-20T07:00:00Z',
  weekEnding: '2026-11-19',
  rows: [
    { rowNumber: 2, workpackNumber: 'WP-000457', completionPct: '40', actualHours: '150', source: 'FMS', outcome: 'Read' },
    {
      rowNumber: 3,
      workpackNumber: 'WP-999999',
      completionPct: '40',
      actualHours: '10',
      source: 'FMS',
      outcome: 'Work Pack not found',
    },
  ],
};

type Call = { method: string; path: string; body: unknown };
type Options = {
  validationMessages?: string[];
  upload?: { status: number; body: unknown };
  completeStatus?: number;
  completeDetail?: string;
};

function fakeBackend({
  validationMessages = [],
  upload = { status: 201, body: RESULT },
  completeStatus = 204,
  completeDetail = '',
}: Options = {}) {
  const calls: Call[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    const isJson = (input.headers.get('content-type') ?? '').includes('json');
    const body =
      input.method === 'GET' || !isJson
        ? null
        : await input
            .clone()
            .text()
            .then((t) => (t ? JSON.parse(t) : null));
    calls.push({ method: input.method, path, body });
    if (path === `/api/v1/tasks/${TASK}`) {
      return json(200, {
        key: TASK,
        name: 'Upload FMS completion report',
        elementId: 'UserTask_UploadCompletionReport',
        processInstanceKey: '2251799813692600',
        creationDate: '2026-11-20T07:00:00Z',
        variables: { weekEnding: '2026-11-19' },
        validationMessages,
      });
    }
    if (path === '/api/v1/completion-reports' && input.method === 'POST') {
      return json(upload.status, upload.body, upload.status < 300 ? 'application/json' : 'application/problem+json');
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
  render(<UploadCompletionReport api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('Week ending 19 Nov 2026');
  return { ...backend, user, onDone };
}

async function chooseAndUpload(user: ReturnType<typeof userEvent.setup>, name = 'fms-completion-wk47.xlsx') {
  const file = new File(['bytes'], name, { type: 'application/octet-stream' });
  await user.upload(screen.getByLabelText('Completion report file'), file);
  await user.click(screen.getByRole('button', { name: 'Upload report' }));
}

describe('UploadCompletionReport', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA59_theResultShowsEveryRowWithItsOutcome', async () => {
    const { user, calls } = await renderScreen();

    await chooseAndUpload(user);

    const table = await screen.findByRole('table', { name: 'Result of fms-completion-wk47.xlsx' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('WP-000457')).toBeInTheDocument();
    expect(within(rows[0]).getByText('Read')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Work Pack not found')).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/v1/completion-reports')).toHaveLength(1);
  });

  it('BPA59_completingWithoutAReportIsBlockedBeforeTheCallIsMade', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('Completion report is required')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('BPA59_completingSendsTheLatestUploadsId', async () => {
    const { user, calls, onDone } = await renderScreen();
    await chooseAndUpload(user);
    await screen.findByRole('table', { name: 'Result of fms-completion-wk47.xlsx' });

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body).toEqual({
      variables: { completionReportId: REPORT },
    });
  });

  it('BPA59_aFileLevelOutcomeIsShownAsOneLine', async () => {
    const { user } = await renderScreen({
      upload: { status: 201, body: { ...RESULT, outcome: 'File cannot be read', rows: [] } },
    });

    await chooseAndUpload(user, 'r.xlsx');

    expect(await screen.findByText('File cannot be read')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: /^Result of/ })).not.toBeInTheDocument();
  });

  it('BPA59_theReturnMessageIsShownAfterAnInvalidReport', async () => {
    await renderScreen({ validationMessages: ['No usable rows in the completion report'] });

    expect(await screen.findByText('No usable rows in the completion report')).toBeInTheDocument();
  });

  it('BPA59_theServersRefusalOfTheUploadIsShown', async () => {
    const { user } = await renderScreen({ upload: { status: 413, body: { detail: 'File is larger than 5 MB' } } });

    await chooseAndUpload(user, 'big.xlsx');

    expect(await screen.findByText('File is larger than 5 MB')).toBeInTheDocument();
  });
});
