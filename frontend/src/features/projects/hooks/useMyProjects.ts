import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listMyProjects } from '../services/projectService';

/** The projects of the logged-in user (BPA-77). */
export function useMyProjects(api: Api) {
  return useQuery({ queryKey: ['my-projects'], queryFn: () => listMyProjects(api) });
}
