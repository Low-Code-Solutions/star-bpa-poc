import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { BookHours } from './BookHours';

// BPA-50: the Book daily hours screen (UserTask_BookHours; skill new-task-screen). One React test per screen condition. The rules are the
// backend's (BookHoursApiTest): the screen shows the open week, saves one line at a time through the lines API and shows the server's
// message. Background: today is 18 Nov 2026 (Asia/Riyadh); the open week is 13 to 18 Nov; E1021 and E1099 have time sheets on WP-000457.
const TASK = '2251799813692500';
const SHEET_1 = '11111111-1111-1111-1111-111111111111';
const SHEET_2 = '22222222-2222-2222-2222-222222222222';
const LINE = '33333333-3333-3333-3333-333333333333';

type Call = { method: string; path: string; body: unknown };
type Line = {
  id: string;
  workDate: string;
  phase: string;
  hours: number;
  status: string;
  entrySource: string;
  createdBy: string;
  remarks?: string;
};
type Options = { lines?: Line[]; secondStatus?: string; saveStatus?: number; saveDetail?: string };

const draft = (extra: Partial<Line> = {}): Line => ({
  id: LINE,
  workDate: '2026-11-17',
  phase: 'Cutting',
  hours: 8,
  status: 'Draft',
  entrySource: 'Form',
  createdBy: 'supervisor',
  ...extra,
});

function fakeBackend({ lines = [], secondStatus = 'Draft', saveStatus = 201, saveDetail = '' }: Options = {}) {
  const calls: Call[] = [];
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  const sheet = (id: string, employeeRef: string, submissionStatus: string) => ({
    id,
    employeeRef,
    workpackNumber: 'WP-000457',
    projectRef: 'PRJ-139',
    wbsRef: '11031',
    activityRef: 'A-2201',
    submissionStatus,
  });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    const body =
      input.method === 'GET' || input.method === 'DELETE'
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
          name: 'Book hours',
          elementId: 'UserTask_BookHours',
          processInstanceKey: '2251799813692400',
          workpackNumber: 'WP-000457',
          creationDate: '2026-11-17T06:00:00Z',
          variables: {},
          validationMessages: [],
        });
      case '/api/v1/digital-timesheets':
        return json(200, [sheet(SHEET_1, 'E1021', 'Draft'), sheet(SHEET_2, 'E1099', secondStatus)]);
      case `/api/v1/digital-timesheets/${SHEET_1}/lines`:
      case `/api/v1/digital-timesheets/${SHEET_2}/lines`: {
        if (input.method === 'POST') {
          return saveStatus === 201 ? json(201, { ...draft(), ...(body as object) }) : json(saveStatus, { detail: saveDetail });
        }
        const first = path.includes(SHEET_1);
        return json(200, {
          timesheetId: first ? SHEET_1 : SHEET_2,
          employeeRef: first ? 'E1021' : 'E1099',
          workpackNumber: 'WP-000457',
          submissionStatus: first ? 'Draft' : secondStatus,
          phases: ['Cutting', 'Welding'],
          openWeekStart: '2026-11-13',
          today: '2026-11-18',
          lines: first ? lines : [],
        });
      }
      case `/api/v1/digital-timesheet-lines/${LINE}`:
        return input.method === 'DELETE' ? new Response(null, { status: 204 }) : json(200, { ...draft(), ...(body as object) });
      default:
        return json(404, {});
    }
  });
  // The calls that change something: POST, PUT and DELETE on the lines endpoints
  const saves = (method: string) =>
    calls.filter((c) => c.method === method && c.path.includes('timesheet') && !c.path.startsWith('/api/v1/tasks'));
  return { fetchMock, calls, saves };
}

async function renderScreen(options: Options = {}) {
  const backend = fakeBackend(options);
  vi.stubGlobal('fetch', backend.fetchMock);
  const user = userEvent.setup();
  render(<BookHours api={apiClient()} taskKey={TASK} onDone={vi.fn()} />);
  // The grid has loaded once the first Worker's row is there
  await screen.findByRole('button', { name: /E1021 Cutting 17 Nov 2026/ });
  return { ...backend, user };
}

const cell = (badge: string, phase: string, date: string) =>
  screen.getByRole('button', { name: new RegExp(`${badge} ${phase} ${date}`) });

describe('BookHours', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-18T06:00:00Z')); // Background: today is 18 Nov 2026 (Asia/Riyadh)
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('BPA50_gridShowsTheOpenWeekUpToToday', async () => {
    await renderScreen({ lines: [draft()] });

    const grid = screen.getByRole('table', { name: 'Hours by Worker, phase and day' });
    const days = within(grid)
      .getAllByRole('columnheader')
      .map((h) => h.textContent);
    expect(days).toEqual(
      expect.arrayContaining(['13 Nov 2026', '14 Nov 2026', '15 Nov 2026', '16 Nov 2026', '17 Nov 2026', '18 Nov 2026']),
    );
    expect(days).not.toContain('12 Nov 2026');
    expect(days).not.toContain('19 Nov 2026');
    expect(cell('E1021', 'Cutting', '17 Nov 2026')).toHaveTextContent('8');
    expect(cell('E1099', 'Welding', '18 Nov 2026')).toBeInTheDocument();
  });

  it('BPA50_savingACellBooksALine', async () => {
    const { user, saves } = await renderScreen();

    await user.click(cell('E1021', 'Cutting', '17 Nov 2026'));
    await user.type(screen.getByLabelText('Hours'), '8');
    await user.type(screen.getByLabelText('Remarks'), 'Night shift');
    await user.click(screen.getByRole('button', { name: 'Save line' }));

    await waitFor(() => expect(saves('POST')).toHaveLength(1));
    expect(saves('POST')[0].path).toBe(`/api/v1/digital-timesheets/${SHEET_1}/lines`);
    expect(saves('POST')[0].body).toEqual({ workDate: '2026-11-17', phase: 'Cutting', hours: 8, remarks: 'Night shift' });
  });

  it('BPA50_changingADraftLinePutsTheNewHours', async () => {
    const { user, saves } = await renderScreen({ lines: [draft()] });

    await user.click(cell('E1021', 'Cutting', '17 Nov 2026'));
    const hours = screen.getByLabelText('Hours');
    expect(hours).toHaveValue('8');
    await user.clear(hours);
    await user.type(hours, '9.5');
    await user.click(screen.getByRole('button', { name: 'Save line' }));

    await waitFor(() => expect(saves('PUT')).toHaveLength(1));
    expect(saves('PUT')[0].path).toBe(`/api/v1/digital-timesheet-lines/${LINE}`);
    expect(saves('PUT')[0].body).toMatchObject({ hours: 9.5 });
  });

  it('BPA50_deleteIsOfferedOnlyOnDraftLinesAndAsksToConfirm', async () => {
    const { user, saves } = await renderScreen({ lines: [draft()] });

    await user.click(cell('E1021', 'Cutting', '17 Nov 2026'));
    await user.click(screen.getByRole('button', { name: 'Delete line' }));
    expect(screen.getByText('Delete this line?')).toBeInTheDocument();
    expect(saves('DELETE')).toHaveLength(0); // nothing is deleted before the confirmation
    await user.click(screen.getByRole('button', { name: 'No, keep it' }));
    expect(saves('DELETE')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Delete line' }));
    await user.click(screen.getByRole('button', { name: 'Yes, delete' }));
    await waitFor(() => expect(saves('DELETE')).toHaveLength(1));
    expect(saves('DELETE')[0].path).toBe(`/api/v1/digital-timesheet-lines/${LINE}`);
  });

  it('BPA50_aSubmittedLineCannotBeChangedOrDeleted', async () => {
    const { user, saves } = await renderScreen({ lines: [draft({ status: 'Submitted' })] });

    await user.click(cell('E1021', 'Cutting', '17 Nov 2026'));

    expect(screen.getByText('Line is not editable (status Submitted)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save line' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete line' })).not.toBeInTheDocument();
    expect(saves('PUT')).toHaveLength(0);
  });

  it('BPA50_theServersMessageIsShown', async () => {
    const detail = 'Hours already booked for E1021 on 17 Nov 2026 for phase Cutting';
    const { user } = await renderScreen({ saveStatus: 400, saveDetail: detail });

    await user.click(cell('E1021', 'Cutting', '17 Nov 2026'));
    await user.type(screen.getByLabelText('Hours'), '2');
    await user.click(screen.getByRole('button', { name: 'Save line' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(detail);
  });

  it('BPA50_aRemarkOver250CharactersIsStoppedBeforeTheCall', async () => {
    const { user, saves } = await renderScreen();

    await user.click(cell('E1021', 'Cutting', '17 Nov 2026'));
    await user.type(screen.getByLabelText('Hours'), '8');
    await user.click(screen.getByLabelText('Remarks'));
    await user.paste('x'.repeat(251));
    await user.click(screen.getByRole('button', { name: 'Save line' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Remarks can be at most 250 characters');
    expect(saves('POST')).toHaveLength(0);
  });

  it('BPA50_aCancelledTimesheetTakesNoLines', async () => {
    await renderScreen({ secondStatus: 'Cancelled' });

    expect(screen.getByText('E1099: time sheet Cancelled, no hours can be booked')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /E1099 Cutting 17 Nov 2026/ })).not.toBeInTheDocument();
  });

  it('BPA50_backToMyTasksLeavesTheScreenWithoutCompleting', async () => {
    const backend = fakeBackend();
    vi.stubGlobal('fetch', backend.fetchMock);
    const onClose = vi.fn();
    const onDone = vi.fn();
    const user = userEvent.setup();
    render(<BookHours api={apiClient()} taskKey={TASK} onDone={onDone} onClose={onClose} />);
    await screen.findByRole('button', { name: /E1021 Cutting 17 Nov 2026/ });

    await user.click(screen.getByRole('button', { name: 'Back to my tasks' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onDone).not.toHaveBeenCalled();
    expect(backend.calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
  });

  it('BPA52_theScreenOffersTheTimeSheetUpload', async () => {
    await renderScreen();

    // E2-03b: the upload section sits on the Book hours screen, below the grid (its own tests are in TimesheetFileUpload.test.tsx)
    const upload = screen.getByRole('region', { name: 'Time sheet upload' });
    expect(within(upload).getByRole('button', { name: 'Download template' })).toBeInTheDocument();
    expect(within(upload).getByLabelText('Time sheet file')).toBeInTheDocument();
    expect(within(upload).getByRole('button', { name: 'Upload time sheet' })).toBeInTheDocument();
  });

  it('BPA50_theTaskIsNotCompletedFromThisScreen', async () => {
    await renderScreen();

    // Completing the task means "Send to Timekeeper", a later story (plan Q8): no Complete button on this screen
    expect(screen.queryByRole('button', { name: 'Complete task' })).not.toBeInTheDocument();
    expect(screen.getByRole('form', { name: 'Book daily hours' })).toBeInTheDocument();
  });
});
