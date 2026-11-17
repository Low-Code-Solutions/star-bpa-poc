/**
 * BPA-50: the display checks of the line panel (09 S14, ADR 0004). The texts are the backend's (HourLineChecks), which checks every rule again;
 * the screen only saves a round trip for the three it can see without data. Hours and completion arrive as typed text.
 */
export function lineMessages(hoursText: string, remarks: string, completionText: string): string[] {
  const messages: string[] = [];
  const hours = Number(hoursText.trim());
  if (hoursText.trim() === '' || !Number.isFinite(hours) || hours <= 0) {
    messages.push('Hours must be greater than 0');
  } else if (decimals(hoursText.trim()) > 2) {
    messages.push('Hours can have at most 2 decimal places');
  }
  if (remarks.length > 250) {
    messages.push('Remarks can be at most 250 characters');
  }
  if (completionText.trim() !== '') {
    const pct = Number(completionText.trim());
    if (!Number.isFinite(pct) || pct < 0 || pct > 100 || decimals(completionText.trim()) > 2) {
      messages.push('Reported completion must be between 0 and 100');
    }
  }
  return messages;
}

/** The decimals that count: 7.50 has one. */
function decimals(text: string): number {
  const fraction = text.split('.')[1] ?? '';
  return fraction.replace(/0+$/, '').length;
}
