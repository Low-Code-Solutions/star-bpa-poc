import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { todayInRiyadh } from '@/utils/dates';
import { CreateWorkPack } from './CreateWorkPack';

// BPA-6: the Create Work Pack screen (09 S14: React checks field rules before submission; the save step re-checks them,
// WorkPackApiTest). One test per scenario with a screen part; texts are the save step's own messages. The backend is a fetch
// stub that answers like the real reference lists and task API.
const TASK = '2251799813685612';

type Call = { method: string; path: string; body: unknown };

// businessDate: what GET /api/v1/business-date answers (BPA-82); by default the Riyadh date of the faked browser clock, as a backend
// without a QA date would; 'fail' makes the call answer 500.
function fakeBackend(businessDate: string = todayInRiyadh()) {
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
          name: 'Create Work Pack',
          elementId: 'UserTask_CreateWorkPack',
          processInstanceKey: '2251799813685501',
          creationDate: '2026-10-21T06:00:00Z',
          variables: {},
          validationMessages: [],
        });
      case '/api/v1/business-date':
        return businessDate === 'fail' ? json(500, {}) : json(200, { date: businessDate, zone: 'Asia/Riyadh' });
      case '/api/v1/activities':
        return json(200, [
          {
            activityRef: 'A-2201',
            activityName: 'Synthetic piping module M-01',
            wbsRef: '11031',
            projectRef: 'PRJ-139',
            jobCodeRef: 'JC-139',
            facCrpo: '139',
            facType: 'Module',
            facName: 'M-01',
            discipline: 'Piping',
            baselineStart: '2026-11-01',
            baselineEnd: '2026-11-30',
            budgetedUnits: 1200,
          },
          {
            activityRef: 'A-2299',
            activityName: 'Synthetic activity without plan data',
            wbsRef: '11031',
            projectRef: 'PRJ-139',
            jobCodeRef: 'JC-139',
          },
        ]);
      case '/api/v1/drawings':
        return json(200, [{ drawingRef: 'DWG-118', title: 'Synthetic drawing 118' }]);
      case '/api/v1/materials':
        return json(200, [
          { materialRef: 'MAT-PIPE-06', description: 'Synthetic pipe 6 inch' },
          { materialRef: 'MAT-FLG-02', description: 'Synthetic flange 2 inch' },
        ]);
      case '/api/v1/departments':
        return json(200, [{ departmentRef: 'Fabrication', name: 'Synthetic Fabrication Department' }]);
      case `/api/v1/tasks/${TASK}/complete`:
        return new Response(null, { status: 204 });
      default:
        return json(404, {});
    }
  });
  const completions = () => calls.filter((c) => c.path === `/api/v1/tasks/${TASK}/complete`);
  return { fetchMock, completions };
}

type User = ReturnType<typeof userEvent.setup>;

async function renderScreen(businessDate?: string) {
  const backend = fakeBackend(businessDate);
  vi.stubGlobal('fetch', backend.fetchMock);
  const onDone = vi.fn();
  const user = userEvent.setup();
  render(<CreateWorkPack api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByRole('option', { name: /A-2201/ });
  await screen.findByRole('option', { name: /Fabrication/ });
  return { ...backend, user, onDone };
}

async function addPhase(user: User, phase: string, hours: string) {
  await user.click(screen.getByRole('button', { name: 'Add phase' }));
  const n = screen.getAllByLabelText(/^Phase \d+$/).length;
  await user.selectOptions(screen.getByLabelText(`Phase ${n}`), phase);
  if (hours !== '') {
    await user.type(screen.getByLabelText(`Man hours ${n}`), hours);
  }
}

async function addMaterial(user: User, material: string, phase: string) {
  await user.click(screen.getByRole('button', { name: 'Add material' }));
  const n = screen.getAllByLabelText(/^Material \d+$/).length;
  await user.selectOptions(screen.getByLabelText(`Material ${n}`), material);
  if (phase !== '') {
    await user.selectOptions(screen.getByLabelText(`Material ${n} phase`), phase);
  }
}

function setDate(value: string) {
  // jsdom's date input does not take typed characters; React sees the change event.
  fireEvent.change(screen.getByLabelText('Date to complete'), { target: { value } });
}

/** The Scenario "Work Pack is created with estimates" data, with the activity given. */
async function fillValid(user: User, activity = 'A-2201') {
  await user.selectOptions(screen.getByLabelText('Project activity'), activity);
  await user.selectOptions(screen.getByLabelText('Drawing'), 'DWG-118');
  await user.selectOptions(screen.getByLabelText('Department'), 'Fabrication');
  setDate('2026-11-06');
  await addPhase(user, 'Cutting', '150');
  await addPhase(user, 'Welding', '250');
  await addMaterial(user, 'MAT-PIPE-06', 'Cutting');
  await addMaterial(user, 'MAT-FLG-02', 'Welding');
}

function shownMessages(): string[] {
  const alert = screen.queryByRole('alert');
  return alert
    ? within(alert)
        .queryAllByRole('listitem')
        .map((li) => li.textContent ?? '')
    : [];
}

function contextValue(term: string): string {
  const panel = screen.getByRole('region', { name: 'Activity context' });
  const dt = within(panel).getByText(term, { selector: 'dt' });
  return dt.nextElementSibling?.textContent ?? '';
}

describe('CreateWorkPack', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-21T06:00:00Z')); // Background: today is 21 Oct 2026 (Asia/Riyadh)
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('BPA6_contextPanelShowsActivityReadOnly', async () => {
    const { user } = await renderScreen();

    await user.selectOptions(screen.getByLabelText('Project activity'), 'A-2201');

    expect(contextValue('Project')).toBe('PRJ-139');
    expect(contextValue('Job Code')).toBe('JC-139');
    expect(contextValue('WBS')).toBe('11031');
    expect(contextValue('Activity')).toBe('A-2201 Synthetic piping module M-01');
    expect(contextValue('FAC CRPO')).toBe('139');
    expect(contextValue('FAC Type')).toBe('Module');
    expect(contextValue('FAC Name')).toBe('M-01');
    expect(contextValue('Discipline')).toBe('Piping');
    expect(contextValue('Baseline start')).toBe('01 Nov 2026');
    expect(contextValue('Baseline end')).toBe('30 Nov 2026');
    expect(contextValue('Budgeted units')).toBe('1200');
    // Read-only: the panel holds text, not inputs.
    expect(within(screen.getByRole('region', { name: 'Activity context' })).queryAllByRole('textbox')).toHaveLength(0);
  });

  it('BPA6_activityWithoutPlanDataCanBeUsed', async () => {
    const { user, completions, onDone } = await renderScreen();

    await fillValid(user, 'A-2299');

    expect(contextValue('Baseline start')).toBe('');
    expect(contextValue('Baseline end')).toBe('');
    expect(contextValue('Budgeted units')).toBe('');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(completions()).toHaveLength(1);
    expect((completions()[0].body as { variables: { activityRef: string } }).variables.activityRef).toBe('A-2299');
    expect(onDone).toHaveBeenCalled();
  });

  it('BPA6_requiredFields', async () => {
    const { user, completions } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(shownMessages()).toEqual(
      expect.arrayContaining([
        'Project activity is required',
        'Drawing is required',
        'At least one material is required',
        'Department is required',
        'Date to complete is required',
      ]),
    );

    await addPhase(user, 'Cutting', '');
    await addMaterial(user, 'MAT-PIPE-06', '');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(shownMessages()).toEqual(expect.arrayContaining(['Select a phase for each material', 'Assign Manhour to each phase']));
    expect(completions()).toHaveLength(0);
  });

  it('BPA6_materialPhaseMustBeWorkPackPhase', async () => {
    const { user } = await renderScreen();

    await addPhase(user, 'Cutting', '150');
    await addPhase(user, 'Welding', '250');
    await user.click(screen.getByRole('button', { name: 'Add material' }));

    const options = within(screen.getByLabelText('Material 1 phase'))
      .getAllByRole('option')
      .map((o) => o.textContent);
    expect(options.filter((o) => o !== '')).toEqual(['Cutting', 'Welding']);
  });

  it('BPA6_dateToCompleteBoundary', async () => {
    const { user, completions } = await renderScreen();
    await fillValid(user);

    setDate('2026-10-20');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).toContain('Date to complete cannot be in the past');
    expect(completions()).toHaveLength(0);

    setDate('2026-10-21');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).not.toContain('Date to complete cannot be in the past');
    expect(completions()).toHaveLength(1);

    // Row 3: 20 Oct 2026 21:30 UTC is 21 Oct 2026 00:30 in Riyadh, so 21 Oct is today, not past.
    cleanup();
    vi.unstubAllGlobals();
    vi.setSystemTime(new Date('2026-10-20T21:30:00Z'));
    const late = await renderScreen();
    await fillValid(late.user);
    setDate('2026-10-21');
    await late.user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).not.toContain('Date to complete cannot be in the past');
    expect(late.completions()).toHaveLength(1);

    // Added after review (said in the PR, L15): at the same instant 20 Oct is past in Riyadh but still today in UTC, so a
    // screen that used UTC or the browser's zone would let it through. The date picker's minimum is the Riyadh date too.
    expect(screen.getByLabelText('Date to complete')).toHaveAttribute('min', '2026-10-21');
    setDate('2026-10-20');
    await late.user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).toContain('Date to complete cannot be in the past');
    expect(late.completions()).toHaveLength(1);
  });

  // BPA-82 (ASSUMPTION: scenarios are the plan's, the Jira task has none): the screen's "today" is the backend's business date, so it
  // agrees with the save step's re-check when QA has set a date (BPA-81). The browser's clock here says 21 Oct 2026.
  const dateMin = () => screen.getByLabelText('Date to complete');

  it('BPA82_screenUsesTheBackendDateWhenItIsLater', async () => {
    const { user, completions } = await renderScreen('2026-12-01');
    await fillValid(user);
    await waitFor(() => expect(dateMin()).toHaveAttribute('min', '2026-12-01'));

    setDate('2026-11-30');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).toContain('Date to complete cannot be in the past');
    expect(completions()).toHaveLength(0);

    setDate('2026-12-01');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).not.toContain('Date to complete cannot be in the past');
    expect(completions()).toHaveLength(1);
  });

  it('BPA82_screenUsesTheBackendDateWhenItIsEarlier', async () => {
    const { user, completions } = await renderScreen('2026-10-15');
    await fillValid(user);
    await waitFor(() => expect(dateMin()).toHaveAttribute('min', '2026-10-15'));

    // 16 Oct is past by the browser's clock (21 Oct) but not by the backend's date
    setDate('2026-10-16');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).not.toContain('Date to complete cannot be in the past');
    expect(completions()).toHaveLength(1);
  });

  it('BPA82_screenFallsBackToTheBrowserDateWhenTheCallFails', async () => {
    const { user, completions } = await renderScreen('fail');
    await fillValid(user);
    // no minimum while the call is pending; the browser's date once it has failed
    await waitFor(() => expect(dateMin()).toHaveAttribute('min', '2026-10-21'));

    setDate('2026-10-20');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(shownMessages()).toContain('Date to complete cannot be in the past');
    expect(completions()).toHaveLength(0);

    setDate('2026-10-21');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));
    expect(completions()).toHaveLength(1);
  });

  it('BPA6_manHoursBoundary', async () => {
    const rows: Array<[string, string | null]> = [
      ['0', 'Man hours must be greater than 0'],
      ['0.01', null],
      ['0.001', 'Man hours can have at most 2 decimal places'],
      ['99999.99', null],
      ['100000', 'Man hours cannot exceed 99,999.99'],
    ];
    const manHourMessages = rows.map(([, m]) => m).filter((m): m is string => m !== null);
    for (const [hours, message] of rows) {
      const { user, completions } = await renderScreen();
      await fillValid(user);
      const cutting = screen.getByLabelText('Man hours 1');
      await user.clear(cutting);
      await user.type(cutting, hours);

      await user.click(screen.getByRole('button', { name: 'Complete task' }));

      if (message) {
        expect(shownMessages(), hours).toContain(message);
        expect(completions(), hours).toHaveLength(0);
      } else {
        expect(
          shownMessages().filter((m) => manHourMessages.includes(m)),
          hours,
        ).toEqual([]);
        expect(completions(), hours).toHaveLength(1);
      }
      cleanup();
      vi.unstubAllGlobals();
    }
  }, 20_000); // five full form fills, one per Examples row
});
