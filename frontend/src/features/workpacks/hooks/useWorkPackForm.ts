import { useState } from 'react';
import { useBusinessDate } from '@/features/reference-data';
import type { Task } from '@/features/tasks/tasks.types';
import type { Api } from '@/services/apiClient';
import { fieldMessages } from '../fieldMessages';
import type { CreateWorkPackForm, MaterialRow, PhaseRow } from '../workpacks.types';

/**
 * The Work Pack form shared by the Create Work Pack (BPA-6) and Confirm Workpack (BPA-23) screens: its state, the prefill from
 * the task's variables (read allow-list, BPA-26), the screen's field checks and the variables to complete the task with
 * (write allow-list). A saved material line keeps its id, so a correction updates it in place (data model A4, F13). The date checks use the
 * server's business date, read once per screen (BPA-82).
 */
export function useWorkPackForm(api: Api) {
  const today = useBusinessDate(api);
  const [activityRef, setActivityRef] = useState('');
  const [drawingRef, setDrawingRef] = useState('');
  const [departmentRef, setDepartmentRef] = useState('');
  const [completionDate, setCompletionDate] = useState('');
  const [phases, setPhases] = useState<PhaseRow[]>([]);
  const [materials, setMaterials] = useState<MaterialRow[]>([]);

  const form: CreateWorkPackForm = { activityRef, drawingRef, departmentRef, completionDate, phases, materials };

  /** The task's variables: what was entered before a rejected save, or the saved Work Pack on Confirm Workpack. */
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
        v.materials.map((m: { id?: unknown; materialRef?: unknown; phase?: unknown }) => ({
          ...(typeof m.id === 'string' && m.id ? { id: m.id } : {}),
          materialRef: text(m.materialRef),
          phase: text(m.phase),
        })),
      );
    }
  }

  function variables() {
    return {
      activityRef,
      drawingRef,
      departmentRef,
      completionDate,
      phases: phases.map((p) => ({ phase: p.phase, manhours: Number(p.manhours) })),
      materials: materials.map((m) => ({ ...(m.id ? { id: m.id } : {}), materialRef: m.materialRef, phase: m.phase })),
    };
  }

  return {
    form,
    setActivityRef,
    setDrawingRef,
    setDepartmentRef,
    setCompletionDate,
    setPhases,
    setMaterials,
    prefill,
    today,
    check: () => fieldMessages(form, today),
    variables,
  };
}

export type WorkPackFormState = ReturnType<typeof useWorkPackForm>;
