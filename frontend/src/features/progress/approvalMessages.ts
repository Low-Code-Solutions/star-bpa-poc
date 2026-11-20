import type { WorkpackProgressRow } from '@/types/api';

/** A % is a plain decimal (no exponent, hexadecimal, comma or text), as the backend reads it. */
const PLAIN_DECIMAL = /^[+-]?(\d+(\.\d*)?|\.\d+)$/;

export type Edit = { pct?: string; remark?: string };

/** The % a changed approval is compared with: the reported %, or the last locked % for a Work Pack with no report row (D6; plan Q9). */
export function referencePct(row: WorkpackProgressRow): number {
  return row.noReportRow || row.reportedPct === undefined ? row.lastLockedPct : row.reportedPct;
}

/** The approved % as typed (or the pre-filled one) and the remark as typed. */
export function entryOf(row: WorkpackProgressRow, edit: Edit | undefined): { pctText: string; remark: string } {
  return { pctText: edit?.pct ?? String(row.approvedPct), remark: edit?.remark ?? row.remark ?? '' };
}

function decimals(text: string): number {
  const fraction = text.trim().split('.')[1] ?? '';
  return fraction.replace(/0+$/, '').length;
}

/**
 * BPA-60: the display checks of the Approve completion % screen (09 S14, ADR 0004). "Completion % can have at most 2 decimal places" and "A remark is required
 * when the approved % differs from the reported %" are Jira's texts; the others are the backend's ASSUMPTIONs (plan Q11). The backend checks all of them again
 * before the task completes. Only the first message is returned.
 */
export function approvalMessages(rows: WorkpackProgressRow[], edits: Record<string, Edit>): string[] {
  const entries = rows.map((row) => ({ row, ...entryOf(row, edits[row.workpackNumber]) }));
  if (entries.some((e) => !PLAIN_DECIMAL.test(e.pctText.trim()))) {
    return ['Completion % must be a number'];
  }
  if (entries.some((e) => decimals(e.pctText) > 2)) {
    return ['Completion % can have at most 2 decimal places'];
  }
  if (entries.some((e) => Number(e.pctText) < 0 || Number(e.pctText) > 100)) {
    return ['Completion % must be between 0 and 100'];
  }
  if (entries.some((e) => Number(e.pctText) !== referencePct(e.row) && e.remark.trim() === '')) {
    return ['A remark is required when the approved % differs from the reported %'];
  }
  if (entries.some((e) => e.remark.trim().length > 250)) {
    return ['Remark can be at most 250 characters'];
  }
  return [];
}
