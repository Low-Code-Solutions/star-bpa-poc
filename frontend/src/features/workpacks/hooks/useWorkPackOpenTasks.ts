import { useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import { listWorkPackOpenTasks } from '../services/workpackService';

/** The open tasks on a Work Pack, from every group (BPA-73). undefined while it loads, null when the read failed; the page does not depend on it. */
export function useWorkPackOpenTasks(api: Api, workpackNumber: string) {
  return useQuery({
    queryKey: ['workpacks', 'open-tasks', workpackNumber],
    queryFn: () => listWorkPackOpenTasks(api, workpackNumber),
  });
}
