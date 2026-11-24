import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listUsers } from '../services/userProjectService';

/** The portal users with their groups (BPA-76). */
export function useUsers(api: Api) {
  return useQuery({ queryKey: ['users'], queryFn: () => listUsers(api) });
}
