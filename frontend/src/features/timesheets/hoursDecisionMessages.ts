export type HoursDecision = '' | 'Validated' | 'Approved' | 'Returned' | 'Rejected';

/**
 * BPA-53, BPA-56: the display checks of the hours decision screens (09 S14, ADR 0004). The texts are the backend's (TaskValidity), which checks them again
 * before the task completes: no decision, or a Returned or Rejected decision without a reason.
 */
export function hoursDecisionMessages(decision: HoursDecision, reason: string): string[] {
  if (decision === '') {
    return ['Decision is required'];
  }
  if ((decision === 'Returned' || decision === 'Rejected') && reason.trim() === '') {
    return ['Reason is required'];
  }
  return [];
}
