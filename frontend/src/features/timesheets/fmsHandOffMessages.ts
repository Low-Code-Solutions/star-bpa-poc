export type FmsOutcome = '' | 'Sent to FMS' | 'Rejected by FMS';

export type FmsChoice = { outcome: FmsOutcome; reason: string };

export const FMS_REASON_MAX = 250;

/**
 * BPA-58: the display checks of the Send hours to FMS screen (09 S14, ADR 0004). "Reason is required" is the story's text and 250 its limit; the other
 * two texts are ASSUMPTIONs. The backend (TaskValidity) checks them again before the task completes.
 */
export function fmsHandOffMessages(choices: FmsChoice[]): string[] {
  if (choices.some((c) => c.outcome === '')) {
    return ['Outcome is required for each file'];
  }
  if (choices.some((c) => c.outcome === 'Rejected by FMS' && c.reason.trim() === '')) {
    return ['Reason is required'];
  }
  if (choices.some((c) => c.outcome === 'Rejected by FMS' && c.reason.trim().length > FMS_REASON_MAX)) {
    return ['Reason can be at most 250 characters'];
  }
  return [];
}
