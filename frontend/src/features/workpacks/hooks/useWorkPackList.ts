import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listWorkPacks, type WorkPackFilter } from '../services/workpackService';

/** The Work Packs list for these filters (BPA-72). undefined while it loads, null when the call failed; the rows stay while the next filter loads. */
export function useWorkPackList(api: Api, filter: WorkPackFilter) {
  return useQuery({
    queryKey: ['workpacks', 'list', filter],
    queryFn: () => listWorkPacks(api, filter),
    placeholderData: keepPreviousData,
  });
}
