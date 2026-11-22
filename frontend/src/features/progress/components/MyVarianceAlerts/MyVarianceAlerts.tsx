import { useQuery } from '@tanstack/react-query';
import { Button } from '@/components/common/Button';
import type { Api } from '@/services/apiClient';
import { VARIANCE_ALERT_GROUPS } from '@/utils/constants';
import { listMyVarianceAlerts } from '../../services/progressService';

/**
 * BPA-64: the "My alerts" list on the home page (plan Q11, an ASSUMPTION: no UI decision exists): the session user's in-system man-hour variance alerts, newest
 * first, as the backend sends them (the text of the alert). Only cost-controller and project-manager see it; the backend answers 403 to the other groups and sends only
 * the session user's own alerts.
 */
export function MyVarianceAlerts({ api, groups }: { api: Api; groups: string[] }) {
  if (!groups.some((group) => VARIANCE_ALERT_GROUPS.includes(group))) {
    return null;
  }
  return <AlertsSection api={api} />;
}

function AlertsSection({ api }: { api: Api }) {
  const query = useQuery({ queryKey: ['variance-alerts'], queryFn: () => listMyVarianceAlerts(api) });
  const alerts = query.data;
  return (
    <section aria-label="My alerts" className="prints">
      <h2>My alerts</h2>
      <Button onClick={() => void query.refetch()}>Refresh</Button>
      {query.isFetched && alerts === null && <p className="notice notice--error">The alerts could not be loaded</p>}
      {alerts && alerts.length === 0 && <p>No variance alerts</p>}
      {alerts && alerts.length > 0 && (
        <ul className="prints__list">
          {alerts.map((alert) => (
            <li key={`${alert.alertType}-${alert.alertDate}-${alert.cbsCodeRef}-${alert.sentAt}`} className="prints__row">
              <span>{alert.message}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
