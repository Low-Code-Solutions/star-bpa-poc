import { useState } from 'react';
import { Button } from '@/components/common/Button';
import { Field } from '@/components/common/Field';
import {
  ActivitySelect,
  DepartmentSelect,
  DrawingSelect,
  MaterialSelect,
  useActivities,
  useDepartments,
  useDrawings,
  useMaterials,
} from '@/features/reference-data';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { PHASES } from '@/utils/constants';
import { showDate, todayInRiyadh } from '@/utils/dates';
import { fieldMessages } from '../../fieldMessages';
import type { MaterialRow, PhaseRow } from '../../workpacks.types';

export { fieldMessages };

/**
 * BPA-6: the Create Work Pack task screen (UserTask_CreateWorkPack) on the shared TaskScreenShell, completed through the
 * backend task API (S14). Field spec: docs/form-specs/Form_CreateWorkPack.form; variable names: the bpa.tasks write
 * allow-list (BPA-26).
 */
export function CreateWorkPack({ api, taskKey, onDone }: TaskScreenProps) {
  const activities = useActivities(api);
  const drawings = useDrawings(api);
  const materialList = useMaterials(api);
  const departments = useDepartments(api);
  const [activityRef, setActivityRef] = useState('');
  const [drawingRef, setDrawingRef] = useState('');
  const [departmentRef, setDepartmentRef] = useState('');
  const [completionDate, setCompletionDate] = useState('');
  const [phases, setPhases] = useState<PhaseRow[]>([]);
  const [materials, setMaterials] = useState<MaterialRow[]>([]);

  /**
   * After a rejected save the task comes back with the data entered before, read back through the task's read allow-list
   * (BPA-26), so the Analyst corrects instead of retyping.
   */
  function prefill(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
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
  }

  const activity = activities.find((a) => a.activityRef === activityRef);
  // D16: a material's phase is one of this Work Pack's phases.
  const enteredPhases = PHASES.filter((p) => phases.some((row) => row.phase === p));

  const setPhase = (i: number, change: Partial<PhaseRow>) =>
    setPhases((rows) => rows.map((row, n) => (n === i ? { ...row, ...change } : row)));
  const setMaterial = (i: number, change: Partial<MaterialRow>) =>
    setMaterials((rows) => rows.map((row, n) => (n === i ? { ...row, ...change } : row)));

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Create Work Pack"
      onDone={onDone}
      onLoaded={prefill}
      check={() => fieldMessages({ activityRef, drawingRef, departmentRef, completionDate, phases, materials })}
      variables={() => ({
        activityRef,
        drawingRef,
        departmentRef,
        completionDate,
        phases: phases.map((p) => ({ phase: p.phase, manhours: Number(p.manhours) })),
        materials: materials.map((m) => ({ materialRef: m.materialRef, phase: m.phase })),
      })}
    >
      <Field label="Project activity">
        <ActivitySelect activities={activities} value={activityRef} onChange={setActivityRef} />
      </Field>

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

      <Field label="Drawing">
        <DrawingSelect drawings={drawings} value={drawingRef} onChange={setDrawingRef} />
      </Field>

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
        <Button onClick={() => setPhases((rows) => [...rows, { phase: '', manhours: '' }])}>Add phase</Button>
      </fieldset>

      <fieldset>
        <legend>Materials</legend>
        {materials.map((row, i) => (
          <div key={i}>
            <MaterialSelect
              aria-label={`Material ${i + 1}`}
              materials={materialList}
              value={row.materialRef}
              onChange={(materialRef) => setMaterial(i, { materialRef })}
            />
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
        <Button onClick={() => setMaterials((rows) => [...rows, { materialRef: '', phase: '' }])}>Add material</Button>
      </fieldset>

      <Field label="Department">
        <DepartmentSelect departments={departments} value={departmentRef} onChange={setDepartmentRef} />
      </Field>

      <Field label="Date to complete">
        <input type="date" value={completionDate} min={todayInRiyadh()} onChange={(e) => setCompletionDate(e.target.value)} />
      </Field>
    </TaskScreenShell>
  );
}
