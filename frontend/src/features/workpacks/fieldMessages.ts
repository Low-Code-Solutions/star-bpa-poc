import type { CreateWorkPackForm } from './workpacks.types';

/**
 * The Create Work Pack screen's field checks (09 S14, D56): required, man hours > 0 / 2 decimals / 99,999.99, no past date.
 * The save step re-checks all of these and the cross-field rules (duplicate phase or material) and returns its own messages.
 * {@code today} is the business date (Riyadh) from the server, so the check agrees with the save step (BPA-82); undefined while it is not yet
 * known: the past-date check is skipped and the save step decides.
 */
export function fieldMessages(form: CreateWorkPackForm, today?: string): string[] {
  const messages: string[] = [];
  const add = (m: string) => {
    if (!messages.includes(m)) {
      messages.push(m);
    }
  };
  if (!form.activityRef) add('Project activity is required');
  if (!form.drawingRef) add('Drawing is required');
  if (form.materials.length === 0) add('At least one material is required');
  if (form.materials.some((m) => !m.phase)) add('Select a phase for each material');
  if (form.phases.some((p) => p.manhours.trim() === '')) add('Assign Manhour to each phase');
  if (!form.departmentRef) add('Department is required');
  if (!form.completionDate) add('Date to complete is required');
  for (const p of form.phases) {
    const text = p.manhours.trim();
    if (text === '') continue;
    const hours = Number(text);
    // More than 2 decimals = not a whole number of hundredths. Checked on the number, so "1e-3" is caught like "0.001".
    const moreThanTwoDecimals = Math.round(hours * 100) / 100 !== hours;
    if (!(hours > 0)) add('Man hours must be greater than 0');
    else if (moreThanTwoDecimals) add('Man hours can have at most 2 decimal places');
    else if (hours > 99999.99) add('Man hours cannot exceed 99,999.99');
  }
  if (form.phases.some((p) => !p.phase)) add('Select a phase for each man-hour line');
  if (form.materials.some((m) => !m.materialRef)) add('Select a material for each material line');
  if (today && form.completionDate && form.completionDate < today) add('Date to complete cannot be in the past');
  return messages;
}
