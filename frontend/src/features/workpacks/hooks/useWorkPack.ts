import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { getWorkPack } from '../services/workpackService';

/** The Work Pack detail (BPA-73). undefined while it loads, null when the call failed, { notFound } for an unknown number. */
export function useWorkPack(api: Api, workpackNumber: string) {
  return useQuery({ queryKey: ['workpacks', 'detail', workpackNumber], queryFn: () => getWorkPack(api, workpackNumber) });
}
