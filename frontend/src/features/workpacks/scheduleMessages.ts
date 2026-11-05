/**
 * The Schedule Work Pack screen's field checks (09 S14, BPA-11): both planned dates are entered. The lead-time rule and the
 * end-before-start rule belong to the save step (ServiceTask_SaveWorkPackSchedule, D56), which returns the task showing them: the
 * screen does not know the lead time, which is configuration on the backend (D9). The two texts are ASSUMPTIONs (the story
 * states none); the save step answers with the same ones.
 */
export function scheduleMessages(plannedStart: string, plannedEnd: string): string[] {
  const messages: string[] = [];
  if (plannedStart === '') {
    messages.push('Planned start is required');
  }
  if (plannedEnd === '') {
    messages.push('Planned end is required');
  }
  return messages;
}
