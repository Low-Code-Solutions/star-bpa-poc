import { useInfiniteQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { getWorkPackHistory, HISTORY_PAGE_SIZE } from '../services/workpackService';

/** The history of a Work Pack (BPA-73), page by page; only read once the History tab is open (`enabled`). */
export function useWorkPackHistory(api: Api, workpackNumber: string, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: ['workpacks', 'history', workpackNumber],
    enabled,
    initialPageParam: 0,
    queryFn: async ({ pageParam }) => {
      const page = await getWorkPackHistory(api, workpackNumber, pageParam);
      if (!page) {
        throw new Error('The history could not be loaded');
      }
      return page;
    },
    getNextPageParam: (last, pages) => (pages.length * HISTORY_PAGE_SIZE < last.total ? pages.length : undefined),
  });
}
