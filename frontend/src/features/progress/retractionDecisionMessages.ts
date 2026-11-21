export type RetractionChoice = { decision: '' | 'Approved' | 'Declined'; reason: string };

/**
 * BPA-63: the display checks of the Approve progress retraction screen (09 S14, ADR 0004). "Reason is required" is Jira's text; "Decision is required" is the
 * backend's (TaskValidity); "Reason can be at most 250 characters" is an ASSUMPTION (the 250 is D8). The backend checks them again before the task completes.
 * Only the first message is returned.
 */
export function retractionDecisionMessages(choices: RetractionChoice[]): string[] {
  if (choices.some((c) => c.decision === '')) {
    return ['Decision is required'];
  }
  if (choices.some((c) => c.decision === 'Declined' && c.reason.trim() === '')) {
    return ['Reason is required'];
  }
  if (choices.some((c) => c.decision === 'Declined' && c.reason.trim().length > 250)) {
    return ['Reason can be at most 250 characters'];
  }
  return [];
}
