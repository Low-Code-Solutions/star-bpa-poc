import type { CbsActualRow } from '@/types/api';

/**
 * BPA-64: the display checks of the Review actual man-hours screen (09 S14, ADR 0004). "A remark is required for <CBS> (alert raised)" is Jira's text (FRD-22
 * §22.5.6.5: a remark for every CBS with an alert, optional for the others); "Remark can be at most 500 characters" is an ASSUMPTION (the column's limit). The backend
 * checks both again before the task completes. Only the first message is returned.
 */
export function reviewMessages(rows: CbsActualRow[], remarks: Record<string, string>): string[] {
  const remarkOf = (row: CbsActualRow) => remarks[row.cbsCodeRef] ?? row.remark ?? '';
  const missing = rows.find((row) => row.alert && remarkOf(row).trim() === '');
  if (missing) {
    return [`A remark is required for ${missing.cbsCodeRef} (alert raised)`];
  }
  if (rows.some((row) => remarkOf(row).trim().length > 500)) {
    return ['Remark can be at most 500 characters'];
  }
  return [];
}
