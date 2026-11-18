export type HoursDecision = '' | 'Validated' | 'Returned';

/**
 * BPA-53: the display checks of the Validate hours screen (09 S14, ADR 0004). The texts are the backend's (TaskValidity), which checks them again before
 * the task completes: no decision, or a return without a reason.
 */
export function hoursDecisionMessages(decision: HoursDecision, reason: string): string[] {
  if (decision === '') {
    return ['Decision is required'];
  }
  if (decision === 'Returned' && reason.trim() === '') {
    return ['Reason is required'];
  }
  return [];
}
