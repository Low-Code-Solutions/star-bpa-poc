import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { getLookAhead } from '../services/workpackService';

/**
 * The Look-Ahead PPC Plan-Dates of a Work Pack's drawing (BPA-11), read once the Work Pack number is known. undefined while it
 * loads, null when the call failed, otherwise the drawing and the dates (none when the drawing has no Look-Ahead row).
 */
export function useLookAhead(api: Api, workpackNumber: string) {
  return useQuery({
    queryKey: ['workpacks', workpackNumber, 'look-ahead'],
    queryFn: () => getLookAhead(api, workpackNumber),
    enabled: workpackNumber !== '',
  }).data;
}
