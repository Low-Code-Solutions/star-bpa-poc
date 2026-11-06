import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { AssignmentFileUpload } from './AssignmentFileUpload';

// BPA-12: the "Assignment file" section on the home page for the Production Team (D58). There is no user task, so no task screen: the
// upload and its result sit on the page (new-task-screen conventions: field check on screen, the backend's texts shown as they come,
// tests named BPA12_<scenario> where a scenario has a screen part). Row validation is BPA-22: rows read "Pending" until then.
const RESULT = {
  id: '3f0c7a52-9e1c-4d6e-8d52-0a1b2c3d4e5f',
  fileName: 'assign-01.xlsx',
  receivedAt: '2026-10-30T06:00:00Z',
  rows: [
    { rowNumber: 2, workpackNumber: 'WP-000457', badgeNo: 'E1021', workerName: 'Worker E1021', outcome: 'Pending' },
    { rowNumber: 3, workpackNumber: 'WP-000457', badgeNo: 'E1099', workerName: 'Worker E1099', outcome: 'Pending' },
  ],
};

type Call = { method: string; path: string; accept: string | null; contentType: string | null };

type Options = { upload?: { status: number; body: unknown }; later?: unknown };

function fakeBackend({ upload = { status: 201, body: RESULT }, later = RESULT }: Options = {}) {
  const calls: Call[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    calls.push({
      method: input.method,
      path,
      accept: input.headers.get('accept'),
      contentType: input.headers.get('content-type'),
    });
    if (path === '/api/v1/assignment-files' && input.method === 'POST') {
      return json(upload.status, upload.body, upload.status < 300 ? 'application/json' : 'application/problem+json');
    }
    if (path === `/api/v1/assignment-files/${RESULT.id}/result`) {
      return (input.headers.get('accept') ?? '').includes('text/csv')
        ? new Response('rowNumber,workpackNumber,badgeNo,workerName,outcome,wpOutcome\n', {
            status: 200,
            headers: { 'Content-Type': 'text/csv' },
          })
        : json(200, later);
    }
    if (path === '/api/v1/assignment-files/template') {
      return new Response(new Blob(['xlsx']), {
        status: 200,
        headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
      });
    }
    return json(404, {});
  });
  return { fetchMock, calls: () => calls };
}

async function renderSection(groups: string[], options: Options = {}) {
  const backend = fakeBackend(options);
  vi.stubGlobal('fetch', backend.fetchMock);
  const user = userEvent.setup();
  render(<AssignmentFileUpload api={apiClient()} groups={groups} />);
  return { ...backend, user };
}

async function chooseAndUpload(user: ReturnType<typeof userEvent.setup>, name = 'assign-01.xlsx') {
  const file = new File(['xlsx bytes'], name, { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
  await user.upload(await screen.findByLabelText('Assignment file'), file);
  await user.click(screen.getByRole('button', { name: 'Upload' }));
}

describe('AssignmentFileUpload', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:download-1');
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('BPA12_otherRolesCannotUpload', async () => {
    const { calls } = await renderSection(['production-planner']);

    expect(screen.queryByRole('region', { name: /Assignment file/ })).not.toBeInTheDocument();
    expect(calls()).toHaveLength(0);
  });

  it('blocks an upload with no file chosen, with the contract text, and sends nothing', async () => {
    const { user, calls } = await renderSection(['production-team']);

    await user.click(await screen.findByRole('button', { name: 'Upload' }));

    expect(await screen.findByText('File is required')).toBeInTheDocument();
    expect(calls().filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('BPA12_fileForScheduledWorkPackIsRouted', async () => {
    // The upload's result on screen: every row with its Worker name and its outcome, "Pending" until BPA-22 validates the rows.
    const { user, calls } = await renderSection(['production-team']);
    // The file goes as multipart form data. jsdom's FormData is not Node's, so the stubbed Request cannot report the content type:
    // what the screen builds is checked instead (the file under the part name "file", as the contract has it).
    const append = vi.spyOn(FormData.prototype, 'append');

    await chooseAndUpload(user);

    const table = await screen.findByRole('table', { name: 'Result of assign-01.xlsx' });
    expect(within(table).getByText('WP-000457', { selector: 'tbody tr:first-child td' })).toBeInTheDocument();
    expect(within(table).getByText('Worker E1021')).toBeInTheDocument();
    expect(within(table).getByText('Worker E1099')).toBeInTheDocument();
    expect(within(table).getAllByText('Pending')).toHaveLength(2);
    expect(calls().some((c) => c.method === 'POST' && c.path === '/api/v1/assignment-files')).toBe(true);
    expect(append).toHaveBeenCalledWith('file', expect.objectContaining({ name: 'assign-01.xlsx' }));
  });

  it('BPA12_fileHasNoRows', async () => {
    // A file-level problem is one line and no table (the contract: a successful call with `outcome` set).
    const { user } = await renderSection(['production-team'], {
      upload: {
        status: 201,
        body: {
          id: RESULT.id,
          fileName: 'assign-empty.xlsx',
          receivedAt: RESULT.receivedAt,
          outcome: 'File has no rows',
          rows: [],
        },
      },
    });

    await chooseAndUpload(user, 'assign-empty.xlsx');

    expect(await screen.findByText('File has no rows')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('BPA12_uploadIsRecorded', async () => {
    // The result can be downloaded: Download result (CSV) asks for text/csv (GET /api/v1/assignment-files/{id}/result).
    const { user, calls } = await renderSection(['production-team']);
    await chooseAndUpload(user);
    await screen.findByRole('table', { name: 'Result of assign-01.xlsx' });

    await user.click(screen.getByRole('button', { name: 'Download result (CSV)' }));

    await waitFor(() =>
      expect(
        calls().some(
          (c) =>
            c.method === 'GET' &&
            c.path === `/api/v1/assignment-files/${RESULT.id}/result` &&
            (c.accept ?? '').includes('text/csv'),
        ),
      ).toBe(true),
    );
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());
  });

  it('reads the result again on Refresh result (a row leaves Pending once BPA-22 has validated it)', async () => {
    const later = { ...RESULT, rows: [{ ...RESULT.rows[0], outcome: 'Assigned' }, RESULT.rows[1]] };
    const { user } = await renderSection(['production-team'], { later });
    await chooseAndUpload(user);
    await screen.findByRole('table', { name: 'Result of assign-01.xlsx' });

    await user.click(screen.getByRole('button', { name: 'Refresh result' }));

    expect(await screen.findByText('Assigned')).toBeInTheDocument();
    expect(screen.getAllByText('Pending')).toHaveLength(1);
  });

  it('downloads the template', async () => {
    const { user, calls } = await renderSection(['production-team']);

    await user.click(await screen.findByRole('button', { name: 'Download template' }));

    await waitFor(() =>
      expect(calls().some((c) => c.method === 'GET' && c.path === '/api/v1/assignment-files/template')).toBe(true),
    );
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());
  });

  it('shows the problem text of a refused upload (the contract 400) and a general message otherwise', async () => {
    const refused = await renderSection(['production-team'], {
      upload: { status: 400, body: { status: 400, title: 'Bad Request', detail: 'File is required' } },
    });
    await chooseAndUpload(refused.user);
    expect(await screen.findByText('File is required')).toBeInTheDocument();
    cleanup();

    const broken = await renderSection(['production-team'], { upload: { status: 500, body: { status: 500 } } });
    await chooseAndUpload(broken.user);
    expect(await screen.findByText('The file could not be uploaded')).toBeInTheDocument();
  });

  it('says why an upload was refused for a missing permission or a file that is too large', async () => {
    const forbidden = await renderSection(['production-team'], { upload: { status: 403, body: { status: 403 } } });
    await chooseAndUpload(forbidden.user);
    expect(await screen.findByText('You are not allowed to upload an assignment file')).toBeInTheDocument();
    cleanup();

    const large = await renderSection(['production-team'], { upload: { status: 413, body: { status: 413 } } });
    await chooseAndUpload(large.user);
    expect(await screen.findByText('The file is too large (at most 5 MB)')).toBeInTheDocument();
  });
});
