import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { hoursDecisionMessages, type HoursDecision } from '../../hoursDecisionMessages';
import { getHoursSubmission } from '../../services/hoursSubmissionService';
import { SubmissionLines } from '../SubmissionLines';

/**
 * BPA-56: the Approve hours (Production) task screen (UserTask_ApproveHoursProduction) on the shared TaskScreenShell. The Production Supervisor sees the
 * hours the Timekeeper validated, read-only (they do not edit hours, Jira), and approves, returns or rejects them; Returned and Rejected need a reason,
 * checked here and again by the backend (09 S14), which also refuses the user who sent the hours (segregation of duties, ASSUMPTION; its text is shown
 * as it is). The decision starts empty: the task's read allow-list leaves the Timekeeper's decision out. The labels ("Approve", "Return to Supervisor",
 * "Reject") are ASSUMPTIONs. Variable names: the bpa.tasks write allow-list (hoursDecision, hoursReason).
 */
export function ApproveHoursProduction({ api, taskKey, onDone }: TaskScreenProps) {
  const [workpackNumber, setWorkpackNumber] = useState('');
  const [submissionId, setSubmissionId] = useState('');
  const [decision, setDecision] = useState<HoursDecision>('');
  const [reason, setReason] = useState('');
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

  const needsReason = decision === 'Returned' || decision === 'Rejected';
  const choices: Array<{ value: HoursDecision; label: string }> = [
    { value: 'Approved', label: 'Approve' },
    { value: 'Returned', label: 'Return to Supervisor' },
    { value: 'Rejected', label: 'Reject' },
  ];

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Approve hours (Production)"
      onDone={onDone}
      onLoaded={loaded}
      check={() => hoursDecisionMessages(decision, reason)}
      variables={() => (needsReason ? { hoursDecision: decision, hoursReason: reason.trim() } : { hoursDecision: decision })}
    >
      <label className="field field--number">
        Workpack number
        <input value={workpackNumber} readOnly />
      </label>
      {submission.data && <p>{`Round ${submission.data.roundNo}, sent by ${submission.data.submittedBy}`}</p>}
      <SubmissionLines lines={submission.data?.lines ?? []} />
      <fieldset className="choices">
        <legend>Decision</legend>
        {choices.map((c) => (
          <label key={c.value}>
            <input
              type="radio"
              name="hoursDecision"
              value={c.value}
              checked={decision === c.value}
              onChange={() => setDecision(c.value)}
            />
            {c.label}
          </label>
        ))}
      </fieldset>
      {needsReason && (
        <Field label="Reason">
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
        </Field>
      )}
    </TaskScreenShell>
  );
}
