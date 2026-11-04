import { useEffect, useState, type FormEvent } from 'react';
import type { apiClient } from './api/client';
import type { components } from './api/schema';

type Api = ReturnType<typeof apiClient>;
type Activity = components['schemas']['Activity'];
type Drawing = components['schemas']['Drawing'];
type Material = components['schemas']['Material'];
type Department = components['schemas']['Department'];

// FRD-7 §7.4.
const PHASES = ['Engineering', 'Cutting', 'Welding', 'Finishing'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

type PhaseRow = { phase: string; manhours: string };
type MaterialRow = { materialRef: string; phase: string };

/** Today in Asia/Riyadh as YYYY-MM-DD (D28, 09 S9): business dates are Riyadh dates whatever the browser's zone. */
function todayInRiyadh(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Riyadh', year: 'numeric', month: '2-digit', day: '2-digit' }).format(
    new Date(),
  );
}

/** 2026-11-01 -> 01 Nov 2026, without going through a time zone. */
function showDate(iso?: string): string {
  if (!iso) {
    return '';
  }
  const [y, m, d] = iso.split('-');
  return `${d} ${MONTHS[Number(m) - 1]} ${y}`;
}

/**
 * The screen's field checks (09 S14, D56): required, man hours > 0 / 2 decimals / 99,999.99, no past date. The save step
 * re-checks all of these and the cross-field rules (duplicate phase or material) and returns its own messages.
 */
export function fieldMessages(form: {
  activityRef: string;
  drawingRef: string;
  departmentRef: string;
  completionDate: string;
  phases: PhaseRow[];
  materials: MaterialRow[];
}): string[] {
  const messages: string[] = [];
  const add = (m: string) => {
    if (!messages.includes(m)) {
      messages.push(m);
    }
  };
  if (!form.activityRef) add('Project activity is required');
  if (!form.drawingRef) add('Drawing is required');
  if (form.materials.length === 0) add('At least one material is required');
  if (form.materials.some((m) => !m.phase)) add('Select a phase for each material');
  if (form.phases.some((p) => p.manhours.trim() === '')) add('Assign Manhour to each phase');
  if (!form.departmentRef) add('Department is required');
  if (!form.completionDate) add('Date to complete is required');
  for (const p of form.phases) {
    const text = p.manhours.trim();
    if (text === '') continue;
    const hours = Number(text);
    // More than 2 decimals = not a whole number of hundredths. Checked on the number, so "1e-3" is caught like "0.001".
    const moreThanTwoDecimals = Math.round(hours * 100) / 100 !== hours;
    if (!(hours > 0)) add('Man hours must be greater than 0');
    else if (moreThanTwoDecimals) add('Man hours can have at most 2 decimal places');
    else if (hours > 99999.99) add('Man hours cannot exceed 99,999.99');
  }
  if (form.phases.some((p) => !p.phase)) add('Select a phase for each man-hour line');
  if (form.materials.some((m) => !m.materialRef)) add('Select a material for each material line');
  if (form.completionDate && form.completionDate < todayInRiyadh()) add('Date to complete cannot be in the past');
  return messages;
}

/** BPA-6: the Create Work Pack task screen (UserTask_CreateWorkPack), completed through the backend task API (S14). */
export function CreateWorkPack({ api, taskKey, onDone }: { api: Api; taskKey: string; onDone: () => void }) {
  const [activities, setActivities] = useState<Activity[]>([]);
  const [drawings, setDrawings] = useState<Drawing[]>([]);
  const [materialList, setMaterialList] = useState<Material[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [activityRef, setActivityRef] = useState('');
  const [drawingRef, setDrawingRef] = useState('');
  const [departmentRef, setDepartmentRef] = useState('');
  const [completionDate, setCompletionDate] = useState('');
  const [phases, setPhases] = useState<PhaseRow[]>([]);
  const [materials, setMaterials] = useState<MaterialRow[]>([]);
  const [messages, setMessages] = useState<string[]>([]);

  useEffect(() => {
    api.GET('/api/v1/tasks/{taskKey}', { params: { path: { taskKey } } }).then(({ data }) => {
      // After a rejected save the task comes back with the save step's messages (G2) and the data entered before, read back
      // through the task's read allow-list (BPA-26), so the Analyst corrects instead of retyping.
      setMessages(data?.validationMessages ?? []);
      const v = (data?.variables ?? {}) as Record<string, unknown>;
      const text = (value: unknown) => (typeof value === 'string' ? value : '');
      setActivityRef(text(v.activityRef));
      setDrawingRef(text(v.drawingRef));
      setDepartmentRef(text(v.departmentRef));
      setCompletionDate(text(v.completionDate));
      if (Array.isArray(v.phases)) {
        setPhases(
          v.phases.map((p: { phase?: unknown; manhours?: unknown }) => ({
            phase: text(p.phase),
            manhours: p.manhours === undefined || p.manhours === null ? '' : String(p.manhours),
          })),
        );
      }
      if (Array.isArray(v.materials)) {
        setMaterials(
          v.materials.map((m: { materialRef?: unknown; phase?: unknown }) => ({
            materialRef: text(m.materialRef),
            phase: text(m.phase),
          })),
        );
      }
    });
    api.GET('/api/v1/activities').then(({ data }) => setActivities(data ?? []));
    api.GET('/api/v1/drawings').then(({ data }) => setDrawings(data ?? []));
    api.GET('/api/v1/materials').then(({ data }) => setMaterialList(data ?? []));
    api.GET('/api/v1/departments').then(({ data }) => setDepartments(data ?? []));
  }, [api, taskKey]);

  const activity = activities.find((a) => a.activityRef === activityRef);
  // D16: a material's phase is one of this Work Pack's phases.
  const enteredPhases = PHASES.filter((p) => phases.some((row) => row.phase === p));

  async function submit(event: FormEvent) {
    event.preventDefault();
    const form = { activityRef, drawingRef, departmentRef, completionDate, phases, materials };
    const blocked = fieldMessages(form);
    setMessages(blocked);
    if (blocked.length > 0) {
      return;
    }
    const { error, response } = await api.POST('/api/v1/tasks/{taskKey}/complete', {
      params: { path: { taskKey } },
      body: {
        variables: {
          activityRef,
          drawingRef,
          departmentRef,
          completionDate,
          phases: phases.map((p) => ({ phase: p.phase, manhours: Number(p.manhours) })),
          materials: materials.map((m) => ({ materialRef: m.materialRef, phase: m.phase })),
        },
      },
    });
    if (response.ok) {
      onDone();
    } else {
      setMessages([(error as { detail?: string } | undefined)?.detail ?? `The task could not be completed (${response.status})`]);
    }
  }

  const setPhase = (i: number, change: Partial<PhaseRow>) =>
    setPhases((rows) => rows.map((row, n) => (n === i ? { ...row, ...change } : row)));
  const setMaterial = (i: number, change: Partial<MaterialRow>) =>
    setMaterials((rows) => rows.map((row, n) => (n === i ? { ...row, ...change } : row)));

  return (
    <form onSubmit={submit} noValidate aria-label="Create Work Pack">
      <h2>Create Work Pack</h2>
      {messages.length > 0 && (
        <div role="alert">
          <ul>
            {messages.map((m) => (
              <li key={m}>{m}</li>
            ))}
          </ul>
        </div>
      )}

      <label>
        Project activity
        <select value={activityRef} onChange={(e) => setActivityRef(e.target.value)}>
          <option value="" />
          {activities.map((a) => (
            <option key={a.activityRef} value={a.activityRef}>{`${a.activityRef} ${a.activityName}`}</option>
          ))}
        </select>
      </label>

      <section aria-label="Activity context">
        <dl>
          <dt>Project</dt>
          <dd>{activity?.projectRef ?? ''}</dd>
          <dt>Job Code</dt>
          <dd>{activity?.jobCodeRef ?? ''}</dd>
          <dt>WBS</dt>
          <dd>{activity?.wbsRef ?? ''}</dd>
          <dt>Activity</dt>
          <dd>{activity ? `${activity.activityRef} ${activity.activityName}` : ''}</dd>
          <dt>FAC CRPO</dt>
          <dd>{activity?.facCrpo ?? ''}</dd>
          <dt>FAC Type</dt>
          <dd>{activity?.facType ?? ''}</dd>
          <dt>FAC Name</dt>
          <dd>{activity?.facName ?? ''}</dd>
          <dt>Discipline</dt>
          <dd>{activity?.discipline ?? ''}</dd>
          <dt>Baseline start</dt>
          <dd>{showDate(activity?.baselineStart)}</dd>
          <dt>Baseline end</dt>
          <dd>{showDate(activity?.baselineEnd)}</dd>
          <dt>Budgeted units</dt>
          <dd>{activity?.budgetedUnits === undefined ? '' : String(activity.budgetedUnits)}</dd>
        </dl>
      </section>

      <label>
        Drawing
        <select value={drawingRef} onChange={(e) => setDrawingRef(e.target.value)}>
          <option value="" />
          {drawings.map((d) => (
            <option key={d.drawingRef} value={d.drawingRef}>{`${d.drawingRef} ${d.title}`}</option>
          ))}
        </select>
      </label>

      <fieldset>
        <legend>Phases and man hours</legend>
        {phases.map((row, i) => (
          <div key={i}>
            <select aria-label={`Phase ${i + 1}`} value={row.phase} onChange={(e) => setPhase(i, { phase: e.target.value })}>
              <option value="" />
              {PHASES.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
            <input
              aria-label={`Man hours ${i + 1}`}
              type="number"
              inputMode="decimal"
              value={row.manhours}
              onChange={(e) => setPhase(i, { manhours: e.target.value })}
            />
          </div>
        ))}
        <button type="button" onClick={() => setPhases((rows) => [...rows, { phase: '', manhours: '' }])}>
          Add phase
        </button>
      </fieldset>

      <fieldset>
        <legend>Materials</legend>
        {materials.map((row, i) => (
          <div key={i}>
            <select
              aria-label={`Material ${i + 1}`}
              value={row.materialRef}
              onChange={(e) => setMaterial(i, { materialRef: e.target.value })}
            >
              <option value="" />
              {materialList.map((m) => (
                <option key={m.materialRef} value={m.materialRef}>{`${m.materialRef} ${m.description}`}</option>
              ))}
            </select>
            <select
              aria-label={`Material ${i + 1} phase`}
              value={row.phase}
              onChange={(e) => setMaterial(i, { phase: e.target.value })}
            >
              <option value="" />
              {enteredPhases.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </div>
        ))}
        <button type="button" onClick={() => setMaterials((rows) => [...rows, { materialRef: '', phase: '' }])}>
          Add material
        </button>
      </fieldset>

      <label>
        Department
        <select value={departmentRef} onChange={(e) => setDepartmentRef(e.target.value)}>
          <option value="" />
          {departments.map((d) => (
            <option key={d.departmentRef} value={d.departmentRef}>{`${d.departmentRef} ${d.name}`}</option>
          ))}
        </select>
      </label>

      <label>
        Date to complete
        <input type="date" value={completionDate} min={todayInRiyadh()} onChange={(e) => setCompletionDate(e.target.value)} />
      </label>

      <button type="submit">Complete task</button>
    </form>
  );
}
