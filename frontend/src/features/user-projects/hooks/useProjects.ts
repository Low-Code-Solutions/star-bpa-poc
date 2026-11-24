import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listProjects } from '../services/userProjectService';

/** The projects that can be assigned to a user (BPA-76). */
export function useProjects(api: Api) {
  return useQuery({ queryKey: ['projects'], queryFn: () => listProjects(api) });
}
