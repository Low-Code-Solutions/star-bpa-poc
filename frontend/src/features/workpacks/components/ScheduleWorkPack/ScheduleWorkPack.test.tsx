import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ScheduleWorkPack } from './ScheduleWorkPack';

// BPA-11: the Schedule Work Pack task screen (09 S14; skill new-task-screen). One React test per screen condition. The lead-time
// and order rules are the save step's (ScheduleApiTest, ScheduleValidatorTest): the task comes back showing them. The fake backend
// answers like the real task API and the Look-Ahead read API. The variable names are the data model's, plannedStartDate and
// plannedEndDate, not the form spec's (BPA-46 Q2).
const TASK = '2251799813692000';

type Call = { method: string; path: string; body: unknown };

type Options = { lookAhead?: Record<string, string>; task?: Record<string, unknown>; lookAheadStatus?: number };

const LOOK_AHEAD = {
  workpackNumber: 'WP-000457',
  drawingRef: 'DWG-118',
  shopDrgMarkPlanDate: '2026-11-03',
  assemblyPlanDate: '2026-11-10',
  weldMapPlanDate: '2026-11-05',
};

function fakeBackend({ lookAhead = LOOK_AHEAD, task = {}, lookAheadStatus = 200 }: Options = {}) {
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
          name: 'Schedule Work Pack',
          elementId: 'UserTask_ScheduleWorkPack',
          processInstanceKey: '2251799813691986',
          workpackNumber: 'WP-000457',
          creationDate: '2026-10-21T06:00:00Z',
          variables: {
            activityRef: 'A-2201',
            drawingRef: lookAhead.drawingRef,
            departmentRef: 'Fabrication',
            completionDate: '2026-11-06',
          },
          validationMessages: [],
          ...task,
        });
      case '/api/v1/workpacks/WP-000457/look-ahead':
        return json(lookAheadStatus, lookAheadStatus === 200 ? lookAhead : {});
      case `/api/v1/tasks/${TASK}/complete`:
        return new Response(null, { status: 204 });
      default:
        return json(404, {});
    }
  });
  const completions = () => calls.filter((c) => c.path === `/api/v1/tasks/${TASK}/complete`);
  return { fetchMock, completions };
}

async function renderScreen(options: Options = {}) {
  const backend = fakeBackend(options);
  vi.stubGlobal('fetch', backend.fetchMock);
  const onDone = vi.fn();
  const user = userEvent.setup();
  render(<ScheduleWorkPack api={apiClient()} taskKey={TASK} onDone={onDone} />);
  // The task has loaded once its Workpack number is shown (prefilled from the task).
  await screen.findByDisplayValue('WP-000457');
  return { ...backend, user, onDone };
}

function enter(start: string, end: string) {
  fireEvent.change(screen.getByLabelText('Planned start'), { target: { value: start } });
  fireEvent.change(screen.getByLabelText('Planned end'), { target: { value: end } });
}

function shownMessages(): string[] {
  const alert = screen.queryByRole('alert');
  return alert
    ? within(alert)
        .queryAllByRole('listitem')
        .map((li) => li.textContent ?? '')
    : [];
}

describe('ScheduleWorkPack', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-21T06:00:00Z')); // Background: today is 21 Oct 2026 (Asia/Riyadh)
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('BPA11_workPackIsScheduledWithLookAheadDates', async () => {
    const { user, completions, onDone } = await renderScreen();

    // The three PPC Plan-Dates are shown read-only with those labels, and the date to complete.
    const plan = await screen.findByRole('region', { name: 'Look-Ahead PPC plan dates' });
    for (const [label, date] of [
      ['Shop Drg Mark', '03 Nov 2026'],
      ['Assembly / Integration', '10 Nov 2026'],
      ['Weld Map Drawing', '05 Nov 2026'],
    ]) {
      expect(within(plan).getByText(label)).toBeInTheDocument();
      expect(within(plan).getByText(date)).toBeInTheDocument();
    }
    expect(within(plan).queryAllByRole('textbox')).toHaveLength(0);
    const details = screen.getByRole('region', { name: 'Work Pack details' });
    expect(within(details).getByText('Date to complete')).toBeInTheDocument();
    expect(within(details).getByText('06 Nov 2026')).toBeInTheDocument();

    enter('2026-11-03', '2026-11-06');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(completions()).toHaveLength(1);
    expect(completions()[0].body).toEqual({ variables: { plannedStartDate: '2026-11-03', plannedEndDate: '2026-11-06' } });
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
  });

  it('BPA11_lateEndShowsNotice', async () => {
    const { user, completions, onDone } = await renderScreen();

    // One day after the date to complete is already late (pins the comparison against "equal": the next test).
    enter('2026-11-03', '2026-11-07');
    expect(screen.getByText('Planned end is after the date to complete (06 Nov 2026)')).toBeInTheDocument();
    enter('2026-11-03', '2026-11-12');

    expect(screen.getByText('Planned end is after the date to complete (06 Nov 2026)')).toBeInTheDocument();
    // Non-blocking: the task still completes with those dates (D26).
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(completions()[0].body).toEqual({ variables: { plannedStartDate: '2026-11-03', plannedEndDate: '2026-11-12' } });
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
  });

  it('BPA11_endEqualToDateToCompleteShowsNoNotice', async () => {
    await renderScreen();

    enter('2026-11-03', '2026-11-06');

    expect(screen.queryByText(/after the date to complete/)).not.toBeInTheDocument();
  });

  it('BPA11_noLookAheadRowForDrawing', async () => {
    // The API answers 200 with the drawing and no dates (the contract's x-assumption).
    const { user, completions, onDone } = await renderScreen({
      lookAhead: { workpackNumber: 'WP-000457', drawingRef: 'DWG-121' },
    });

    expect(await screen.findByText('No Look-Ahead plan dates for DWG-121')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Look-Ahead PPC plan dates' })).not.toBeInTheDocument();
    // "And I can still enter planned dates and complete the task"
    enter('2026-11-03', '2026-11-06');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(completions()).toHaveLength(1);
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
  });

  it('shows no Look-Ahead dates, and no claim that there are none, when the look-ahead call fails (added after review)', async () => {
    const { user, completions } = await renderScreen({ lookAheadStatus: 500 });

    // Give the failed query time to settle, then check that nothing was claimed; the Planner can still schedule.
    await waitFor(() => expect(screen.getByLabelText('Planned start')).toBeInTheDocument());
    expect(screen.queryByRole('region', { name: 'Look-Ahead PPC plan dates' })).not.toBeInTheDocument();
    expect(screen.queryByText(/No Look-Ahead plan dates/)).not.toBeInTheDocument();
    enter('2026-11-03', '2026-11-06');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(completions()).toHaveLength(1);
  });

  it('shows "Not set" for a plan date the Look-Ahead row does not have (added after review)', async () => {
    await renderScreen({
      lookAhead: { workpackNumber: 'WP-000457', drawingRef: 'DWG-118', shopDrgMarkPlanDate: '2026-11-03' },
    });

    const plan = await screen.findByRole('region', { name: 'Look-Ahead PPC plan dates' });
    expect(within(plan).getByText('03 Nov 2026')).toBeInTheDocument();
    expect(within(plan).getAllByText('Not set')).toHaveLength(2);
  });

  it('blocks a completion with a missing date (ASSUMPTION texts)', async () => {
    const { user, completions } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(shownMessages()).toEqual(['Planned start is required', 'Planned end is required']);
    expect(completions()).toHaveLength(0);
  });

  it("leaves the lead-time and order rules to the save step, and shows the step's messages with the dates entered", async () => {
    // A task sent back by ServiceTask_SaveWorkPackSchedule: its validationMessages, and the dates the Planner entered.
    const { user, completions } = await renderScreen({
      task: {
        validationMessages: ['Date cannot be earlier than 28 Oct 2026'],
        variables: {
          activityRef: 'A-2201',
          drawingRef: 'DWG-118',
          departmentRef: 'Fabrication',
          completionDate: '2026-11-06',
          plannedStartDate: '2026-10-27',
          plannedEndDate: '2026-11-06',
        },
      },
    });

    expect(shownMessages()).toEqual(['Date cannot be earlier than 28 Oct 2026']);
    expect(screen.getByLabelText('Planned start')).toHaveValue('2026-10-27');
    expect(screen.getByLabelText('Planned end')).toHaveValue('2026-11-06');
    // A date before today + lead time is not blocked here: the screen does not know the lead time (D9, config on the backend).
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(completions()).toHaveLength(1);
  });
});
