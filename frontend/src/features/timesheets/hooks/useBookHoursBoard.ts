import { useQueries, useQuery } from '@tanstack/react-query';
import type { Api } from '@/services/apiClient';
import type { TimesheetLines } from '@/types/api';
import { getTimesheetLines, listTimesheets } from '../services/timesheetService';

/**
 * The Book hours grid's data (BPA-50): the Work Pack's time sheets, then each one's lines, phases and the open week. A query key per
 * time sheet, so a save refreshes only that Worker's row (invalidate ['timesheet-lines', id]).
 */
export function useBookHoursBoard(api: Api, workpackNumber: string): TimesheetLines[] {
  const sheets = useQuery({
    queryKey: ['timesheets', workpackNumber],
    queryFn: () => listTimesheets(api, workpackNumber),
    enabled: workpackNumber !== '',
  });
  const ids = (sheets.data ?? []).flatMap((s) => (s.id ? [s.id] : []));
  const lines = useQueries({
    queries: ids.map((id) => ({ queryKey: ['timesheet-lines', id], queryFn: () => getTimesheetLines(api, id) })),
  });
  return lines.flatMap((q) => (q.data ? [q.data] : [])).sort((a, b) => a.employeeRef.localeCompare(b.employeeRef));
}
