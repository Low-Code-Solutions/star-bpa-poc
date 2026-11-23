import { useState } from 'react';
import { Link } from 'react-router';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Spinner } from '@/components/common/Spinner';
import type { Api } from '@/services/apiClient';
import { showDate } from '@/utils/dates';
import { useWorkPack } from '../../hooks/useWorkPack';
import { LifecycleBar } from '../LifecycleBar';
import { OpenTasksPanel } from '../OpenTasksPanel';
import { EMPTY, HistoryTab, HoursTab, OverviewTab, ProgressTab, WorkersTab, two } from './tabs';

const TABS = ['Overview', 'Workers', 'Hours', 'Progress', 'History'] as const;
type Tab = (typeof TABS)[number];

/**
 * BPA-73: the Work Pack as one case view, read only. A summary panel and the lifecycle bar, five tabs (Overview, Workers, Hours, Progress, History) and a side
 * panel of the open tasks from every group. Nothing here changes the Work Pack: the actions stay on each group's task screens. An unknown number says
 * "Work Pack not found". The list this page is opened from (BPA-72) is not limited to the user's projects until BPA-79, and neither is this page.
 */
export function WorkPackDetail({ api, workpackNumber }: { api: Api; workpackNumber: string }) {
  const [tab, setTab] = useState<Tab>('Overview');
  const { data } = useWorkPack(api, workpackNumber);

  return (
    <section aria-label={`Work Pack ${workpackNumber}`} className="work-pack-detail">
      <h2>Work Pack {workpackNumber}</h2>
      <p>
        <Link to="/workpacks">Back to the Work Packs list</Link>
      </p>
      {data === undefined ? <Spinner /> : null}
      {data === null ? <ErrorMessage text="The Work Pack could not be loaded" /> : null}
      {data && 'notFound' in data ? <p>Work Pack not found</p> : null}
      {data && 'detail' in data ? (
        <div className="work-pack-detail__body">
          <div className="work-pack-detail__main">
            <dl className="context-panel">
              <dt>Status</dt>
              <dd>{data.detail.summary.status}</dd>
              <dt>Assignment state</dt>
              <dd>{data.detail.summary.assignmentState ?? EMPTY}</dd>
              <dt>Activity</dt>
              <dd>{data.detail.summary.activityRef}</dd>
              <dt>Project</dt>
              <dd>{data.detail.summary.projectRef}</dd>
              <dt>Drawing</dt>
              <dd>{data.detail.summary.drawingRef}</dd>
              <dt>Department</dt>
              <dd>{data.detail.summary.departmentRef}</dd>
              <dt>Planned</dt>
              <dd>
                {data.detail.summary.plannedStartDate
                  ? `${showDate(data.detail.summary.plannedStartDate)} to ${showDate(data.detail.summary.plannedEndDate ?? undefined)}`
                  : EMPTY}
              </dd>
              <dt>Approved %</dt>
              <dd>{two(data.detail.summary.approvedPct)}</dd>
              <dt>Earned MH (activity)</dt>
              <dd>{two(data.detail.summary.earnedManhours)}</dd>
            </dl>
            <LifecycleBar status={data.detail.summary.status} />
            <div role="tablist" aria-label="Work Pack sections" className="tabs">
              {TABS.map((t) => (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  id={`tab-${t}`}
                  aria-selected={tab === t}
                  aria-controls={tab === t ? `panel-${t}` : undefined}
                  onClick={() => setTab(t)}
                >
                  {t}
                </button>
              ))}
            </div>
            <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
              {tab === 'Overview' ? <OverviewTab detail={data.detail} /> : null}
              {tab === 'Workers' ? <WorkersTab detail={data.detail} /> : null}
              {tab === 'Hours' ? <HoursTab detail={data.detail} /> : null}
              {tab === 'Progress' ? <ProgressTab detail={data.detail} /> : null}
              {tab === 'History' ? <HistoryTab api={api} workpackNumber={workpackNumber} /> : null}
            </div>
          </div>
          <OpenTasksPanel api={api} workpackNumber={workpackNumber} />
        </div>
      ) : null}
    </section>
  );
}
