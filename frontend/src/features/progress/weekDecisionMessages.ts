// BPA-66: the Approve weekly progress screen's checks, in the backend's texts (TaskValidity checks them again). "Reason is required" is the story's; "Decision is required" is
// the task API's existing text.

export type WeekChoice = {
  decision: '' | 'Approved' | 'ChangesRequested';
  target: 'Progress' | 'CostReview';
  reason: string;
};

/** The messages that block completion; empty when the Project Manager's choice is complete. */
export function weekDecisionMessages(choice: WeekChoice): string[] {
  if (choice.decision === '') {
    return ['Decision is required'];
  }
  if (choice.decision === 'ChangesRequested' && choice.reason.trim() === '') {
    return ['Reason is required'];
  }
  return [];
}
