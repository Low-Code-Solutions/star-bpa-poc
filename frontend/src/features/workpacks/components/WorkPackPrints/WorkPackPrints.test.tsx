import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { WorkPackPrints } from './WorkPackPrints';

// BPA-10: the "Workpack prints" list on the home page (there is no task screen for the print). It lists the Work Packs that have a
// print, each with a "Print PDF" button; the PDF is fetched as a blob and shown in an iframe with the browser's viewer (09 S4,
// ADR 0004). Only the groups allowed to read a print see it (time-method-analyst, production-planner, production-team).
const PRINTS = [
  {
    workpackNumber: 'WP-000461',
    drawingRef: 'DWG-121',
    plannedStartDate: '2026-11-10',
    plannedEndDate: '2026-11-12',
    generatedAt: '2026-10-21T07:00:00Z',
  },
  {
    workpackNumber: 'WP-000457',
    drawingRef: 'DWG-118',
    plannedStartDate: '2026-11-03',
    plannedEndDate: '2026-11-06',
    generatedAt: '2026-10-21T06:00:00Z',
  },
];

type Call = { method: string; path: string };

function fakeBackend({ prints = PRINTS, printStatus = 200 }: { prints?: unknown[]; printStatus?: number } = {}) {
  const calls: Call[] = [];
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    calls.push({ method: input.method, path });
    if (path === '/api/v1/workpack-prints') {
      return json(200, prints);
    }
    if (path === '/api/v1/workpacks/WP-000457/print') {
      return printStatus === 200
        ? new Response(new Blob(['%PDF-1.7 test'], { type: 'application/pdf' }), {
            status: 200,
            headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': 'inline; filename="WP-000457.pdf"' },
          })
        : json(printStatus, { status: printStatus, detail: 'Workpack print not available' }, 'application/problem+json');
    }
    return json(404, {});
  });
  return { fetchMock, calls: () => calls };
}

async function renderPanel(groups: string[], options: Parameters<typeof fakeBackend>[0] = {}) {
  const backend = fakeBackend(options);
  vi.stubGlobal('fetch', backend.fetchMock);
  const user = userEvent.setup();
  render(<WorkPackPrints api={apiClient()} groups={groups} />);
  return { ...backend, user };
}

describe('WorkPackPrints', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:print-1');
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('lists the printed Work Packs, each with a Print PDF button', async () => {
    await renderPanel(['time-method-analyst']);

    const section = await screen.findByRole('region', { name: 'Workpack prints' });
    expect(await within(section).findByText('WP-000461')).toBeInTheDocument();
    expect(within(section).getByText('WP-000457')).toBeInTheDocument();
    expect(within(section).getByText('DWG-118')).toBeInTheDocument();
    expect(within(section).getByText('03 Nov 2026 to 06 Nov 2026')).toBeInTheDocument();
    expect(within(section).getByRole('button', { name: 'Print PDF WP-000457' })).toBeInTheDocument();
    expect(within(section).getByRole('button', { name: 'Print PDF WP-000461' })).toBeInTheDocument();
  });

  it('says so when no Work Pack has a print yet', async () => {
    await renderPanel(['production-planner'], { prints: [] });

    expect(await screen.findByText('No Workpack prints yet')).toBeInTheDocument();
  });

  it('BPA10_printIsServedInline', async () => {
    // The PDF comes from GET /api/v1/workpacks/{n}/print as a blob and is shown in an iframe (the browser's PDF viewer, S4).
    const { user, calls } = await renderPanel(['time-method-analyst']);

    await user.click(await screen.findByRole('button', { name: 'Print PDF WP-000457' }));

    const frame = await screen.findByTitle('Workpack print WP-000457');
    expect(frame.tagName).toBe('IFRAME');
    expect(frame).toHaveAttribute('src', 'blob:print-1');
    expect(calls().some((c) => c.method === 'GET' && c.path === '/api/v1/workpacks/WP-000457/print')).toBe(true);
  });

  it('shows "Workpack print not available" when the print is gone (404)', async () => {
    const { user } = await renderPanel(['time-method-analyst'], { printStatus: 404 });

    await user.click(await screen.findByRole('button', { name: 'Print PDF WP-000457' }));

    expect(await screen.findByText('Workpack print not available')).toBeInTheDocument();
    expect(screen.queryByTitle('Workpack print WP-000457')).not.toBeInTheDocument();
  });

  it('shows a general message, and releases the blob URL, when the print fails (500, then a network error) (added after review)', async () => {
    const { user } = await renderPanel(['time-method-analyst']);
    await user.click(await screen.findByRole('button', { name: 'Print PDF WP-000457' }));
    await screen.findByTitle('Workpack print WP-000457');
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();

    // The same Work Pack, now failing with a 500: the iframe goes, the blob URL is released, and the message is general.
    vi.stubGlobal('fetch', fakeBackend({ printStatus: 500 }).fetchMock);
    await user.click(screen.getByRole('button', { name: 'Print PDF WP-000457' }));
    expect(await screen.findByText('The print could not be loaded')).toBeInTheDocument();
    expect(screen.queryByTitle('Workpack print WP-000457')).not.toBeInTheDocument();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:print-1');

    // A request that throws (no network) is the same message, not an unhandled rejection.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await user.click(screen.getByRole('button', { name: 'Print PDF WP-000457' }));
    expect(await screen.findByText('The print could not be loaded')).toBeInTheDocument();
  });

  it('says the list could not be loaded when that call fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    render(<WorkPackPrints api={apiClient()} groups={['production-planner']} />);

    expect(await screen.findByText('The list of prints could not be loaded')).toBeInTheDocument();
  });

  it('reads the list again on Refresh', async () => {
    const { user, calls } = await renderPanel(['production-team']);
    await screen.findByText('WP-000457');
    const before = calls().filter((c) => c.path === '/api/v1/workpack-prints').length;

    await user.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() => expect(calls().filter((c) => c.path === '/api/v1/workpack-prints').length).toBeGreaterThan(before));
  });

  it('is not shown, and reads nothing, for a user outside the print groups', async () => {
    const { calls } = await renderPanel(['project-engineer']);

    expect(screen.queryByRole('region', { name: 'Workpack prints' })).not.toBeInTheDocument();
    expect(calls()).toHaveLength(0);
  });
});
