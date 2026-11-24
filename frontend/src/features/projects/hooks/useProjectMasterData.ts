import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { getProjectMasterData } from '../services/projectService';

/** The master data of one project (BPA-77); nothing is read until a project is picked. A refusal is a result, not an error. */
export function useProjectMasterData(api: Api, projectRef: string) {
  return useQuery({
    queryKey: ['project-master-data', projectRef],
    queryFn: () => getProjectMasterData(api, projectRef),
    enabled: projectRef !== '',
  });
}
