import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@tests/test-utils';
import { apiClient } from '@/services/apiClient';
import { MyVarianceAlerts } from './MyVarianceAlerts';

// BPA-64: the "My alerts" list on the home page (plan Q11, an ASSUMPTION: no UI decision exists): the session user's in-system man-hour alerts, newest first, for
// cost-controller and project-manager (delta §7). The backend sends only the user's own alerts and answers 403 to the other groups.
const ALERTS = [
  {
    weekEnding: '2026-11-26',
    cbsCodeRef: 'CBS-139-PIP-01',
    alertType: 'Manhour',
    message: 'Man-hours on CBS-139-PIP-01 are 25.00% over the Current Forecast (1250.00 of 1000.00 MH), week ending 26 Nov 2026',
    sentAt: '2026-11-27T07:00:00Z',
  },
  {
    weekEnding: '2026-11-19',
    cbsCodeRef: 'CBS-139-PIP-01',
    alertType: 'Manhour',
    message: 'Man-hours on CBS-139-PIP-01 are 20.00% over the Current Forecast (1200.00 of 1000.00 MH), week ending 19 Nov 2026',
    sentAt: '2026-11-20T07:00:00Z',
  },
];

function stub(status: number, body: unknown) {
  const fetchMock = vi.fn(async (input: Request) => {
    const found = new URL(input.url).pathname === '/api/v1/me/variance-alerts';
    return new Response(JSON.stringify(found ? body : {}), {
      status: found ? status : 404,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('MyVarianceAlerts', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('BPA64_theCostControllerSeesTheirAlerts', async () => {
    stub(200, ALERTS);
    render(<MyVarianceAlerts api={apiClient()} groups={['cost-controller']} />);

    expect(await screen.findByText(ALERTS[0].message)).toBeInTheDocument();
    expect(screen.getByText(ALERTS[1].message)).toBeInTheDocument();
    const texts = screen.getAllByRole('listitem').map((li) => li.textContent ?? '');
    expect(texts[0]).toContain('26 Nov 2026');
    expect(texts[1]).toContain('19 Nov 2026');
  });

  it('showsAnEmptyListAndAFailure', async () => {
    stub(200, []);
    render(<MyVarianceAlerts api={apiClient()} groups={['project-manager']} />);
    expect(await screen.findByText('No variance alerts')).toBeInTheDocument();
    cleanup();

    stub(500, {});
    render(<MyVarianceAlerts api={apiClient()} groups={['project-manager']} />);
    expect(await screen.findByText('The alerts could not be loaded')).toBeInTheDocument();
  });

  it('otherGroupsDoNotSeeTheList', () => {
    const fetchMock = stub(200, ALERTS);
    render(<MyVarianceAlerts api={apiClient()} groups={['ppc-team']} />);

    expect(screen.queryByText('My alerts')).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
