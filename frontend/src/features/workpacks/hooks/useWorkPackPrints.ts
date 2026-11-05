import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listWorkPackPrints } from '../services/workpackService';

/**
 * The Work Packs that have a print (BPA-10), newest first. undefined while it loads, null when the call failed. `refresh` reads it
 * again (a print appears a moment after the Work Pack is scheduled).
 */
export function useWorkPackPrints(api: Api) {
  const query = useQuery({ queryKey: ['workpacks', 'prints'], queryFn: () => listWorkPackPrints(api) });
  return { prints: query.data, refresh: () => query.refetch() };
}
