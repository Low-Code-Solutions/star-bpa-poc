import type { Api } from '@/services/apiClient';
import type { Activity, Department, Drawing, Material } from '../reference-data.types';

// The reference lists (09 S20; built by BPA-6, BPA-46 Q4). A failed call gives an empty list, as the screens had.
export async function listActivities(api: Api): Promise<Activity[]> {
  const { data } = await api.GET('/api/v1/activities');
  return data ?? [];
}

export async function listDrawings(api: Api): Promise<Drawing[]> {
  const { data } = await api.GET('/api/v1/drawings');
  return data ?? [];
}

export async function listMaterials(api: Api): Promise<Material[]> {
  const { data } = await api.GET('/api/v1/materials');
  return data ?? [];
}

export async function listDepartments(api: Api): Promise<Department[]> {
  const { data } = await api.GET('/api/v1/departments');
  return data ?? [];
}
