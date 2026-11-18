import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { TimesheetFileUpload } from './TimesheetFileUpload';

// BPA-52: the "Upload time sheet" section on the Book hours screen (UserTask_BookHours; E2-03b). The rules are the backend's (TimesheetFileApiTest): the
// screen sends the file, shows every row with its outcome or the file-level line, and offers the template and the result as downloads. One React test per
// screen condition; the texts come from the backend as they are.
const RESULT = {
  id: '5c6f8a52-9e1c-4d6e-8d52-0a1b2c3d4e5f',
  fileName: 'team-week47.xlsx',
  receivedAt: '2026-11-18T06:00:00Z',
  rows: [
    {
      rowNumber: 2,
      workpackNumber: 'WP-000457',
      badgeNo: 'E1021',
      workDate: '16/11/2026',
      phase: 'Cutting',
      hours: '8',
      outcome: 'Saved',
    },
    {
      rowNumber: 3,
      workpackNumber: 'WP-999999',
      badgeNo: 'E1021',
      workDate: '16/11/2026',
      phase: 'Cutting',
      hours: '8',
      remarks: 'Night shift',
      outcome: 'Work Pack not found',
    },
  ],
};

type Call = { method: string; path: string; accept: string | null };
type Options = { upload?: { status: number; body: unknown } };

function fakeBackend({ upload = { status: 201, body: RESULT } }: Options = {}) {
  const calls: Call[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    calls.push({ method: input.method, path, accept: input.headers.get('accept') });
    if (path === '/api/v1/timesheet-files' && input.method === 'POST') {
      return json(upload.status, upload.body, upload.status < 300 ? 'application/json' : 'application/problem+json');
    }
    if (path === `/api/v1/timesheet-files/${RESULT.id}/result`) {
      return new Response('rowNumber,workpackNumber,badgeNo,workDate,phase,hours,remarks,outcome\n', {
        status: 200,
        headers: { 'Content-Type': 'text/csv' },
      });
    }
    if (path === '/api/v1/timesheet-files/template') {
      return new Response(new Blob(['xlsx']), {
        status: 200,
        headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
      });
    }
    return json(404, {});
  });
  return { fetchMock, calls: () => calls };
}

async function renderSection(options: Options = {}) {
  const backend = fakeBackend(options);
  vi.stubGlobal('fetch', backend.fetchMock);
  const user = userEvent.setup();
  const onSaved = vi.fn();
  render(<TimesheetFileUpload api={apiClient()} onSaved={onSaved} />);
  return { ...backend, user, onSaved };
}

async function chooseAndUpload(user: ReturnType<typeof userEvent.setup>, name = 'team-week47.xlsx') {
  const file = new File(['bytes'], name, { type: 'application/octet-stream' });
  await user.upload(await screen.findByLabelText('Time sheet file'), file);
  await user.click(screen.getByRole('button', { name: 'Upload time sheet' }));
}

describe('TimesheetFileUpload', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:download-1');
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA52_theResultShowsEveryRowWithItsOutcome', async () => {
    const { user, onSaved, calls } = await renderSection();

    await chooseAndUpload(user);

    const table = await screen.findByRole('table', { name: 'Result of team-week47.xlsx' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('Saved')).toBeInTheDocument();
    expect(within(rows[1]).getByText('WP-999999')).toBeInTheDocument();
    expect(within(rows[1]).getByText('16/11/2026')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Night shift')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Work Pack not found')).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledTimes(1); // a row was saved, so the grid reads the lines again
    expect(calls().filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('BPA52_aFileLevelOutcomeIsShownAsOneLine', async () => {
    const { user, onSaved } = await renderSection({
      upload: { status: 201, body: { ...RESULT, fileName: 'empty.xlsx', outcome: 'File has no rows', rows: [] } },
    });

    await chooseAndUpload(user, 'empty.xlsx');

    expect(await screen.findByText('File has no rows')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('BPA52_aFileIsRequired', async () => {
    const { user, calls } = await renderSection();

    await user.click(await screen.findByRole('button', { name: 'Upload time sheet' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('File is required');
    expect(calls().filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('BPA52_aRefusedUploadShowsTheServersText', async () => {
    const { user, onSaved } = await renderSection({
      upload: { status: 413, body: { status: 413, title: 'Content Too Large', detail: 'File is larger than 5 MB' } },
    });

    await chooseAndUpload(user);

    expect(await screen.findByRole('alert')).toHaveTextContent('File is larger than 5 MB');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('BPA52_otherRolesAreToldTheyCannotUpload', async () => {
    const { user } = await renderSection({
      upload: { status: 403, body: { status: 403, title: 'Forbidden', detail: 'Your groups do not allow this call' } },
    });

    await chooseAndUpload(user);

    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to upload a time sheet');
  });

  it('BPA52_theTemplateAndTheResultCanBeDownloaded', async () => {
    const { user, calls } = await renderSection();

    await user.click(await screen.findByRole('button', { name: 'Download template' }));
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalledTimes(1));
    expect(calls().some((c) => c.path === '/api/v1/timesheet-files/template')).toBe(true);

    await chooseAndUpload(user);
    await user.click(await screen.findByRole('button', { name: 'Download result (CSV)' }));
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalledTimes(2));
    expect(calls().find((c) => c.path === `/api/v1/timesheet-files/${RESULT.id}/result`)?.accept).toContain('text/csv');
  });
});
