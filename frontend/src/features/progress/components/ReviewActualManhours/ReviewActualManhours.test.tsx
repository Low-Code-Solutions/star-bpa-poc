import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { ReviewActualManhours } from './ReviewActualManhours';

// BPA-64: the Review actual man-hours screen (UserTask_ReviewActualManhours, group cost-controller). One row per CBS with the actual man-hours, the Current
// Forecast, the ETC (forecast minus actual; a negative ETC is highlighted, with no extra alert), the variance %, the result and the alert, and a remark per CBS,
// required for every CBS with an alert (FRD-22 §22.5.6.5). The lines with no CBS are shown as "No CBS" and never take a remark. The rules are the backend's
// (VarianceApiTest); the highlight class is an ASSUMPTION of the plan.
const TASK = '2251799813699500';
const WEEK = {
  weekEnding: '2026-11-19',
  noCbsManhours: 40,
  cbs: [
    {
      cbsCodeRef: 'CBS-139-PIP-01',
      actualManhours: 1200,
      currentForecastManhours: 1000,
      etc: -200,
      variancePct: 20,
      result: 'OVER_15_PCT',
      alert: true,
    },
    { cbsCodeRef: 'CBS-140-STR-01', actualManhours: 50, result: 'NO_FORECAST', alert: false },
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
        name: 'Review actual man-hours',
        elementId: 'UserTask_ReviewActualManhours',
        processInstanceKey: '2251799813692800',
        creationDate: '2026-11-20T07:00:00Z',
        variables: { weekEnding: '2026-11-19' },
        validationMessages: [],
      });
    }
    if (path === '/api/v1/progress-periods/2026-11-19/cbs-actuals') {
      return json(200, WEEK);
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
  render(<ReviewActualManhours api={apiClient()} taskKey={TASK} onDone={onDone} />);
  await screen.findByText('Week ending 19 Nov 2026');
  await screen.findByText('CBS-139-PIP-01');
  return { ...backend, user, onDone };
}

const rowOf = (cbs: string) => screen.getByText(cbs).closest('tr') as HTMLElement;

describe('ReviewActualManhours', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA64_negativeEtcIsHighlighted', async () => {
    await renderScreen();

    const first = rowOf('CBS-139-PIP-01');
    expect(within(first).getByText('1200.00')).toBeInTheDocument();
    expect(within(first).getByText('1000.00')).toBeInTheDocument();
    expect(within(first).getByText('20.00')).toBeInTheDocument();
    expect(within(first).getByText('-200.00')).toHaveClass('sign-table__cell--negative');
    expect(rowOf('CBS-140-STR-01').querySelector('.sign-table__cell--negative')).toBeNull();
    expect(screen.getByText('No CBS')).toBeInTheDocument();
    expect(screen.getByText('40.00')).toBeInTheDocument();
  });

  it('BPA64_alertedCbsNeedsARemark', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText('A remark is required for CBS-139-PIP-01 (alert raised)')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/complete'))).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('BPA64_costControllerReviewsWithRemarks', async () => {
    const { user, calls, onDone } = await renderScreen();

    await user.type(screen.getByLabelText('Remark for CBS-139-PIP-01'), 'Rework on spools');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(calls.find((c) => c.path === `/api/v1/tasks/${TASK}/complete`)?.body).toEqual({
      variables: {
        cbsReviews: [
          { cbsCodeRef: 'CBS-139-PIP-01', remark: 'Rework on spools' },
          { cbsCodeRef: 'CBS-140-STR-01', remark: '' },
        ],
      },
    });
    expect(screen.queryByLabelText('Remark for No CBS')).not.toBeInTheDocument();
  });

  it('BPA64_theServersRefusalIsShown', async () => {
    const detail = 'Remark can be at most 500 characters';
    const { user, onDone } = await renderScreen({ completeStatus: 400, completeDetail: detail });

    await user.type(screen.getByLabelText('Remark for CBS-139-PIP-01'), 'x');
    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText(detail)).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
