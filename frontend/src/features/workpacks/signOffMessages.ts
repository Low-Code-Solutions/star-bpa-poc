export type SignOffDecision = '' | 'Approved' | 'Declined';

/**
 * The Sign off release screen's field checks (09 S14, BPA-9): a decision is chosen, and a decline has a reason. The backend
 * re-checks both before it completes the task (TaskValidity) and answers with these same texts. "Reason is required" is the
 * story's; "Decision is required" is an ASSUMPTION text.
 */
export function signOffMessages(decision: SignOffDecision, reason: string): string[] {
  if (decision === '') {
    return ['Decision is required'];
  }
  if (decision === 'Declined' && reason.trim() === '') {
    return ['Reason is required'];
  }
  return [];
}
