import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { todayInRiyadh } from '@/utils/dates';
import * as reference from '../services/referenceService';

// One query per list (TanStack Query, ADR 0017). Empty arrays until loaded, as the screens had.
export function useActivities(api: Api) {
  return useQuery({ queryKey: ['reference', 'activities'], queryFn: () => reference.listActivities(api) }).data ?? [];
}

export function useDrawings(api: Api) {
  return useQuery({ queryKey: ['reference', 'drawings'], queryFn: () => reference.listDrawings(api) }).data ?? [];
}

export function useMaterials(api: Api) {
  return useQuery({ queryKey: ['reference', 'materials'], queryFn: () => reference.listMaterials(api) }).data ?? [];
}

export function useDepartments(api: Api) {
  return useQuery({ queryKey: ['reference', 'departments'], queryFn: () => reference.listDepartments(api) }).data ?? [];
}

/**
 * The business date the server uses (BPA-82; 09 S9, ADR 0013): the same "today" as its validators, so the screen agrees with the save step when
 * QA has set a business date (BPA-81). Read afresh whenever a screen opens and when the window regains focus (a screen left open past midnight).
 * If the call fails or has not answered yet, the browser's Riyadh date (ASSUMPTION; the save step re-checks anyway, S14).
 */
export function useBusinessDate(api: Api): string {
  const date = useQuery({
    queryKey: ['business-date'],
    queryFn: () => reference.getBusinessDate(api),
    staleTime: 0,
    refetchOnWindowFocus: true,
  }).data;
  return date ?? todayInRiyadh();
}
