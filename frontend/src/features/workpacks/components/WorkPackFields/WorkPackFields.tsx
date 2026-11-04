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
import type { Api } from '@/services/apiClient';
import { PHASES } from '@/utils/constants';
import { showDate, todayInRiyadh } from '@/utils/dates';
import type { WorkPackFormState } from '../../hooks/useWorkPackForm';
import type { MaterialRow, PhaseRow } from '../../workpacks.types';

/**
 * The Work Pack's editable fields, shared by Create Work Pack (BPA-6) and Confirm Workpack (BPA-23): activity with its read-only
 * context panel (D17), drawing, phases and man hours, materials with a phase from the entered phases (D16), department and
 * date to complete. Field spec: docs/form-specs/Form_CreateWorkPack.form and Form_ConfirmWorkPack.form.
 */
export function WorkPackFields({ api, state }: { api: Api; state: WorkPackFormState }) {
  const activities = useActivities(api);
  const drawings = useDrawings(api);
  const materialList = useMaterials(api);
  const departments = useDepartments(api);
  const { form, setActivityRef, setDrawingRef, setDepartmentRef, setCompletionDate, setPhases, setMaterials } = state;

  const activity = activities.find((a) => a.activityRef === form.activityRef);
  // D16: a material's phase is one of this Work Pack's phases.
  const enteredPhases = PHASES.filter((p) => form.phases.some((row) => row.phase === p));

  const setPhase = (i: number, change: Partial<PhaseRow>) =>
    setPhases((rows) => rows.map((row, n) => (n === i ? { ...row, ...change } : row)));
  const setMaterial = (i: number, change: Partial<MaterialRow>) =>
    setMaterials((rows) => rows.map((row, n) => (n === i ? { ...row, ...change } : row)));

  return (
    <>
      <Field label="Project activity">
        <ActivitySelect activities={activities} value={form.activityRef} onChange={setActivityRef} />
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
        <DrawingSelect drawings={drawings} value={form.drawingRef} onChange={setDrawingRef} />
      </Field>

      <fieldset>
        <legend>Phases and man hours</legend>
        {form.phases.map((row, i) => (
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
        {form.materials.map((row, i) => (
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
        <DepartmentSelect departments={departments} value={form.departmentRef} onChange={setDepartmentRef} />
      </Field>

      <Field label="Date to complete">
        <input
          type="date"
          value={form.completionDate}
          min={todayInRiyadh()}
          onChange={(e) => setCompletionDate(e.target.value)}
        />
      </Field>
    </>
  );
}
