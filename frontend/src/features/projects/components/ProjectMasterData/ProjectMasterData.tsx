import { useState, type ReactNode } from 'react';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Field } from '@/components/common/Field';
import { Spinner } from '@/components/common/Spinner';
import type { Api } from '@/services/apiClient';
import type { ProjectMasterData as MasterData } from '@/types/api';
import { useMyProjects } from '../../hooks/useMyProjects';
import { useProjectMasterData } from '../../hooks/useProjectMasterData';

const TABS = ['Activities', 'Cost structure', 'Drawings', 'Reserved materials', 'Cutting sheets'] as const;
type Tab = (typeof TABS)[number];

const slug = (t: string) => t.toLowerCase().replace(/ /g, '-');
const EMPTY = '–';

/** A tab's table, or "No rows"; the system of record is shown either way (BPA-77: each tab names its system of record). */
function TabTable({
  name,
  systemOfRecord,
  head,
  rows,
}: {
  name: string;
  systemOfRecord: string;
  head: string[];
  rows: ReactNode[][];
}) {
  return (
    <>
      <p>System of record: {systemOfRecord}</p>
      {rows.length === 0 ? (
        <p>No rows</p>
      ) : (
        <table className="sign-table" aria-label={name}>
          <thead>
            <tr>
              {head.map((h) => (
                <th key={h}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((cells, i) => (
              <tr key={i}>
                {cells.map((c, j) => (
                  <td key={j}>{c}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function panelOf(tab: Tab, data: MasterData) {
  switch (tab) {
    case 'Activities':
      return (
        <TabTable
          name="Activities"
          systemOfRecord={data.activities.systemOfRecord}
          head={['Activity', 'Name', 'WBS', 'Job Code', 'Discipline']}
          rows={data.activities.rows.map((a) => [a.activityRef, a.activityName, a.wbsRef, a.jobCodeRef, a.discipline ?? EMPTY])}
        />
      );
    case 'Cost structure':
      return (
        <TabTable
          name="Cost structure"
          systemOfRecord={data.costStructure.systemOfRecord}
          head={['Activity', 'Name', 'CBS code']}
          rows={data.costStructure.rows.map((c) => [c.activityRef, c.activityName, c.cbsCodeRef])}
        />
      );
    case 'Drawings':
      return (
        <TabTable
          name="Drawings"
          systemOfRecord={data.drawings.systemOfRecord}
          head={['Drawing', 'Title', 'Latest revision', 'Status']}
          rows={data.drawings.rows.map((d) => [d.drawingRef, d.title, d.latestRevision ?? EMPTY, d.status ?? EMPTY])}
        />
      );
    case 'Reserved materials':
      return (
        <TabTable
          name="Reserved materials"
          systemOfRecord={data.reservedMaterials.systemOfRecord}
          head={['Traceability number', 'Drawing', 'Material', 'Description']}
          rows={data.reservedMaterials.rows.map((m) => [m.traceabilityNumber, m.drawingRef, m.materialRef, m.description])}
        />
      );
    case 'Cutting sheets':
      return (
        <TabTable
          name="Cutting sheets"
          systemOfRecord={data.cuttingSheets.systemOfRecord}
          head={['Cutting sheet', 'Drawing']}
          rows={data.cuttingSheets.rows.map((s) => [s.cuttingSheetRef, s.drawingRef])}
        />
      );
  }
}

/**
 * BPA-77: Projects and master data, for every persona. A Project picker offers only the user's own projects (GET /api/v1/me/projects; the first is open at the
 * start), five tabs show the master data of the picked project (GET /api/v1/projects/{ref}/master-data), each with the system of record it comes from; the
 * user with no project sees "No projects assigned. Ask the PPC Team.". A project the backend refuses (403, for example an assignment removed after the list was
 * read) shows its text and no tab. Read only: nothing here creates or changes data. The scope is enforced by the backend (ProjectScope); this only shows it.
 */
export function ProjectMasterData({ api }: { api: Api }) {
  const projects = useMyProjects(api);
  const [picked, setPicked] = useState('');
  const [tab, setTab] = useState<Tab>('Activities');
  const selected = picked || projects.data?.[0]?.projectRef || '';
  const master = useProjectMasterData(api, selected);

  if (projects.data === undefined) {
    return <Spinner />;
  }
  if (projects.data === null) {
    return <ErrorMessage text="The projects could not be loaded" />;
  }
  if (projects.data.length === 0) {
    return <p>No projects assigned. Ask the PPC Team.</p>;
  }

  const result = master.data;
  return (
    <section aria-label="Projects and master data" className="project-master-data">
      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <Field label="Project">
          <select value={selected} onChange={(e) => setPicked(e.target.value)}>
            {projects.data.map((p) => (
              <option key={p.projectRef} value={p.projectRef}>
                {p.projectRef}
              </option>
            ))}
          </select>
        </Field>
      </form>
      {result === undefined ? <Spinner /> : null}
      {result && !result.ok ? (
        <ErrorMessage text={result.detail ?? `The master data could not be loaded (${result.status})`} />
      ) : null}
      {result && result.ok ? (
        <>
          <div role="tablist" aria-label="Master data sections" className="tabs">
            {TABS.map((t) => (
              <button
                key={t}
                type="button"
                role="tab"
                id={`tab-${slug(t)}`}
                aria-selected={tab === t}
                aria-controls={tab === t ? `panel-${slug(t)}` : undefined}
                onClick={() => setTab(t)}
              >
                {t}
              </button>
            ))}
          </div>
          <div role="tabpanel" id={`panel-${slug(tab)}`} aria-labelledby={`tab-${slug(tab)}`}>
            {panelOf(tab, result.data)}
          </div>
        </>
      ) : null}
    </section>
  );
}
