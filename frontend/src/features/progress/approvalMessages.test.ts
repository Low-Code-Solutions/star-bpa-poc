import { describe, expect, it } from 'vitest';
import type { WorkpackProgressRow } from '@/types/api';
import { approvalMessages } from './approvalMessages';

// BPA-60: the screen's own checks of the approved % (the backend checks them again). QA review: a % is a plain decimal.
const row: WorkpackProgressRow = {
  workpackNumber: 'WP-000457',
  noReportRow: false,
  reportedPct: 40,
  lastLockedPct: 30,
  approvedHours: 0,
  approvedPct: 40,
};

const withPct = (pct: string) => approvalMessages([row], { 'WP-000457': { pct, remark: 'a remark' } });

describe('approvalMessages', () => {
  it('a plain decimal passes', () => {
    expect(withPct('40')).toEqual([]);
    expect(withPct('40.5')).toEqual([]);
    expect(withPct('40.50')).toEqual([]);
  });

  it('an exponent, a hexadecimal, a comma or text is not a number', () => {
    for (const bad of ['1e1', '0x10', '40,5', 'forty', '', ' ']) {
      expect(withPct(bad), bad).toEqual(['Completion % must be a number']);
    }
  });

  it('three decimals, a value outside 0 to 100 and a missing remark are refused in that order', () => {
    expect(withPct('38.125')).toEqual(['Completion % can have at most 2 decimal places']);
    expect(withPct('101')).toEqual(['Completion % must be between 0 and 100']);
    expect(approvalMessages([row], { 'WP-000457': { pct: '38' } })).toEqual([
      'A remark is required when the approved % differs from the reported %',
    ]);
  });
});
