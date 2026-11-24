import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listUserProjects } from '../services/userProjectService';

/** The projects of one user (BPA-76); nothing is read until a user is picked. */
export function useUserProjects(api: Api, username: string) {
  return useQuery({
    queryKey: ['user-projects', username],
    queryFn: () => listUserProjects(api, username),
    enabled: username !== '',
  });
}
