import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Field } from '@/components/common/Field';
import { Spinner } from '@/components/common/Spinner';
import type { Api } from '@/services/apiClient';
import { showDate } from '@/utils/dates';
import { useWorkPackList } from '../../hooks/useWorkPackList';
import type { WorkPackFilter } from '../../services/workpackService';

const STATUSES = ['Draft', 'Released', 'In Progress', 'Completed'];
const EMPTY = '–';

const two = (value?: number | null) => (value == null ? EMPTY : Number(value).toFixed(2));

/**
 * BPA-72: the Work Packs list, read only. A table of every Work Pack (number, activity, drawing, department, status, assignment state, planned start and end,
 * the latest Locked week's approved completion % and the Earned man-hours of the activity), a status, project and week-ending filter and a search by number.
 * A click on a row opens /workpacks/<number> (BPA-73 builds that page). There is no create, edit or delete here: the list is for finding a Work Pack. The
 * list holds only the Work Packs of the user's projects (BPA-79; the backend limits it).
 */
export function WorkPackList({ api }: { api: Api }) {
  const navigate = useNavigate();
  const [filter, setFilter] = useState<WorkPackFilter>({});
  const all = useWorkPackList(api, {});
  const rows = useWorkPackList(api, filter);
  const projects = [...new Set((all.data ?? []).map((r) => r.projectRef))].sort();
  const change = (patch: WorkPackFilter) => setFilter((f) => ({ ...f, ...patch }));

  return (
    <section aria-label="Work Packs list" className="work-pack-list">
      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <Field label="Status">
          <select value={filter.status ?? ''} onChange={(e) => change({ status: e.target.value })}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Project">
          <select value={filter.project ?? ''} onChange={(e) => change({ project: e.target.value })}>
            <option value="">All</option>
            {projects.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Week ending">
          <input type="date" value={filter.weekEnding ?? ''} onChange={(e) => change({ weekEnding: e.target.value })} />
        </Field>
        <Field label="Work Pack number">
          <input type="search" value={filter.search ?? ''} onChange={(e) => change({ search: e.target.value })} />
        </Field>
      </form>
      {rows.data === undefined ? <Spinner /> : null}
      {rows.data === null ? <ErrorMessage text="The Work Packs could not be loaded" /> : null}
      {rows.data && rows.data.length === 0 ? <p>{filter.search?.trim() ? 'Work Pack not found' : 'No Work Packs'}</p> : null}
      {rows.data && rows.data.length > 0 ? (
        <table className="sign-table" aria-label="Work Packs">
          <thead>
            <tr>
              <th>Work Pack</th>
              <th>Activity</th>
              <th>Drawing</th>
              <th>Department</th>
              <th>Status</th>
              <th>Assignment state</th>
              <th>Planned start</th>
              <th>Planned end</th>
              <th>Approved %</th>
              <th>Earned MH (activity)</th>
            </tr>
          </thead>
          <tbody>
            {rows.data.map((r) => (
              <tr key={r.workpackNumber} className="clickable" onClick={() => navigate(`/workpacks/${r.workpackNumber}`)}>
                <td>
                  <Link to={`/workpacks/${r.workpackNumber}`} onClick={(e) => e.stopPropagation()}>
                    {r.workpackNumber}
                  </Link>
                </td>
                <td>{r.activityRef}</td>
                <td>{r.drawingRef}</td>
                <td>{r.departmentRef}</td>
                <td>{r.status}</td>
                <td>{r.assignmentState ?? EMPTY}</td>
                <td>{r.plannedStartDate ? showDate(r.plannedStartDate) : EMPTY}</td>
                <td>{r.plannedEndDate ? showDate(r.plannedEndDate) : EMPTY}</td>
                <td title={r.approvedWeekEnding ? `Latest Locked week: ${showDate(r.approvedWeekEnding)}` : undefined}>
                  {two(r.approvedPct)}
                </td>
                <td
                  title={
                    r.approvedWeekEnding
                      ? `Activity ${r.activityRef}, latest Locked week: ${showDate(r.approvedWeekEnding)}`
                      : undefined
                  }
                >
                  {two(r.earnedManhours)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </section>
  );
}
