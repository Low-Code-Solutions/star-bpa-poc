import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/common/Button';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import type { HoursSubmissionLine } from '@/types/api';
import { showDate } from '@/utils/dates';
import { hoursDecisionMessages, type HoursDecision } from '../../hoursDecisionMessages';
import { lineMessages } from '../../lineMessages';
import { amendLine, getHoursSubmission } from '../../services/hoursSubmissionService';
import { SubmissionLines } from '../SubmissionLines';

/**
 * BPA-53: the Validate hours task screen (UserTask_ValidateHours) on the shared TaskScreenShell. The Timekeeper sees the hours the Supervisor sent for the
 * Work Pack (read through GET /api/v1/hours-submissions/{id}; the task gives the id as hoursSubmissionId), may amend a line's hours on request with a
 * reason (at most 250 characters, FRD-5-12 §5.12.5.5; the backend checks the budget again and its message is shown as it is), and validates the hours
 * or returns them with a reason. The decision and the reason are checked here and again by the backend (09 S14). Variable names: the bpa.tasks
 * write allow-list (hoursDecision, hoursReason). Texts that are not in the story ("Validate", "Return to Supervisor", labels) are ASSUMPTIONs.
 */
export function ValidateHours({ api, taskKey, onDone }: TaskScreenProps) {
  const [workpackNumber, setWorkpackNumber] = useState('');
  const [submissionId, setSubmissionId] = useState('');
  const [decision, setDecision] = useState<HoursDecision>('');
  const [reason, setReason] = useState('');
  const [amending, setAmending] = useState<HoursSubmissionLine | null>(null);
  const [hours, setHours] = useState('');
  const [amendReason, setAmendReason] = useState('');
  const [amendMessages, setAmendMessages] = useState<string[]>([]);
  const queryClient = useQueryClient();
  const submission = useQuery({
    queryKey: ['hours-submission', submissionId],
    queryFn: () => getHoursSubmission(api, submissionId),
    enabled: submissionId !== '',
  });

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    setWorkpackNumber(task.workpackNumber ?? '');
    setSubmissionId(typeof v.hoursSubmissionId === 'string' ? v.hoursSubmissionId : '');
  }

  function startAmend(line: HoursSubmissionLine) {
    setAmending(line);
    setHours(String(line.hours));
    setAmendReason('');
    setAmendMessages([]);
  }

  async function saveAmendment() {
    if (!amending) {
      return;
    }
    const found = lineMessages(hours, '', '');
    if (amendReason.trim() === '') {
      found.push('Reason is required');
    } else if (amendReason.trim().length > 250) {
      found.push('Reason can be at most 250 characters');
    }
    setAmendMessages(found);
    if (found.length > 0) {
      return;
    }
    const result = await amendLine(api, amending.id, { hours: Number(hours), reason: amendReason.trim() });
    if (result.ok) {
      await queryClient.invalidateQueries({ queryKey: ['hours-submission', submissionId] });
      setAmending(null);
    } else {
      setAmendMessages([result.detail ?? `The amendment could not be saved (${result.status})`]);
    }
  }

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Validate hours"
      onDone={onDone}
      onLoaded={loaded}
      check={() => hoursDecisionMessages(decision, reason)}
      variables={() =>
        decision === 'Returned' ? { hoursDecision: 'Returned', hoursReason: reason.trim() } : { hoursDecision: decision }
      }
    >
      <label className="field field--number">
        Workpack number
        <input value={workpackNumber} readOnly />
      </label>
      {submission.data && <p>{`Round ${submission.data.roundNo}, sent by ${submission.data.submittedBy}`}</p>}
      <SubmissionLines lines={submission.data?.lines ?? []} onAmend={startAmend} />
      {amending && (
        <section
          aria-label="Amend hours"
          className="context-panel"
          // Enter in a field must not submit the task form
          onKeyDown={(e) => {
            if (e.key === 'Enter' && e.target instanceof HTMLInputElement) {
              e.preventDefault();
            }
          }}
        >
          <h3>{`${amending.badgeNo} ${showDate(amending.workDate)} ${amending.phase}`}</h3>
          <ErrorMessage messages={amendMessages} />
          <Field label="Hours" className="field--number">
            <input inputMode="decimal" value={hours} onChange={(e) => setHours(e.target.value)} />
          </Field>
          <Field label="Reason for amending">
            <input value={amendReason} onChange={(e) => setAmendReason(e.target.value)} />
          </Field>
          <Button className="button--primary" onClick={() => void saveAmendment()}>
            Save amendment
          </Button>
          <Button onClick={() => setAmending(null)}>Cancel</Button>
        </section>
      )}
      <fieldset className="choices">
        <legend>Decision</legend>
        <label>
          <input
            type="radio"
            name="hoursDecision"
            value="Validated"
            checked={decision === 'Validated'}
            onChange={() => setDecision('Validated')}
          />
          Validate
        </label>
        <label>
          <input
            type="radio"
            name="hoursDecision"
            value="Returned"
            checked={decision === 'Returned'}
            onChange={() => setDecision('Returned')}
          />
          Return to Supervisor
        </label>
      </fieldset>
      {decision === 'Returned' && (
        <Field label="Reason for returning">
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
        </Field>
      )}
    </TaskScreenShell>
  );
}
