import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
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
