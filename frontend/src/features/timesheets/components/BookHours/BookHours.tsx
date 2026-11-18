import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/common/Button';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import type { DigitalTimesheetLine, NewDigitalTimesheetLine, TimesheetLines } from '@/types/api';
import { daysFrom, showDate } from '@/utils/dates';
import { useBookHoursBoard } from '../../hooks/useBookHoursBoard';
import { lineMessages } from '../../lineMessages';
import { addLine, changeLine, deleteLine, type SaveResult } from '../../services/timesheetService';
import { TimesheetFileUpload } from '../TimesheetFileUpload';

type Selection = { timesheetId: string; phase: string; date: string };

const EDITABLE = ['Draft', 'Returned'];

/**
 * BPA-50: the Book daily hours task screen (UserTask_BookHours) on the shared TaskScreenShell. One row per Worker and phase and one
 * column per day of the open week up to today (E2-D15: any date from the start of the open week up to today); a cell opens a line panel
 * with hours, remark (at most 250 characters) and an optional reported completion % (C-E2-10, information only). Lines are saved one at
 * a time through the lines API, and the backend's message is shown as it is. Delete is offered on Draft lines only, after a confirmation
 * (FRD-5-12 §5.12.8.1). The screen does not complete the task: that means "Send to Timekeeper", a later story (plan Q8), so the shell hides
 * "Complete task" and a button leads back to the task list. The task API gives only the Work Pack number; the time sheets, the phases and
 * the open week come from the lines API.
 */
export function BookHours({ api, taskKey, onDone, onClose }: TaskScreenProps) {
  const [workpackNumber, setWorkpackNumber] = useState('');
  const board = useBookHoursBoard(api, workpackNumber);
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Selection | null>(null);
  const [hours, setHours] = useState('');
  const [remarks, setRemarks] = useState('');
  const [completion, setCompletion] = useState('');
  const [messages, setMessages] = useState<string[]>([]);
  const [confirming, setConfirming] = useState(false);

  const days = board.length > 0 ? daysFrom(board[0].openWeekStart, board[0].today) : [];
  const lineOf = (sheet: TimesheetLines, phase: string, date: string): DigitalTimesheetLine | undefined =>
    sheet.lines.find((l) => l.phase === phase && l.workDate === date && l.status !== 'Rejected');

  const sheet = selected ? board.find((t) => t.timesheetId === selected.timesheetId) : undefined;
  const line = sheet && selected ? lineOf(sheet, selected.phase, selected.date) : undefined;
  const editable = !line || EDITABLE.includes(line.status);

  function select(target: TimesheetLines, phase: string, date: string) {
    const existing = lineOf(target, phase, date);
    setSelected({ timesheetId: target.timesheetId, phase, date });
    setHours(existing ? String(existing.hours) : '');
    setRemarks(existing?.remarks ?? '');
    setCompletion(existing?.reportedCompletionPct != null ? String(existing.reportedCompletionPct) : '');
    setMessages([]);
    setConfirming(false);
  }

  async function finish(result: SaveResult, timesheetId: string) {
    if (result.ok) {
      await queryClient.invalidateQueries({ queryKey: ['timesheet-lines', timesheetId] });
      setSelected(null);
      setMessages([]);
      setConfirming(false);
    } else {
      setMessages([result.detail ?? `The line could not be saved (${result.status})`]);
    }
  }

  async function save() {
    if (!sheet || !selected) {
      return;
    }
    const found = lineMessages(hours, remarks, completion);
    setMessages(found);
    if (found.length > 0) {
      return;
    }
    const optional = {
      ...(remarks !== '' ? { remarks } : {}),
      ...(completion.trim() !== '' ? { reportedCompletionPct: Number(completion) } : {}),
    };
    const result = line
      ? await changeLine(api, line.id, { hours: Number(hours), ...optional })
      : await addLine(api, sheet.timesheetId, {
          workDate: selected.date,
          phase: selected.phase as NewDigitalTimesheetLine['phase'],
          hours: Number(hours),
          ...optional,
        });
    await finish(result, sheet.timesheetId);
  }

  async function remove() {
    if (sheet && line) {
      await finish(await deleteLine(api, line.id), sheet.timesheetId);
    }
  }

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Book daily hours"
      onDone={onDone}
      onLoaded={(task: Task) => setWorkpackNumber(task.workpackNumber ?? '')}
      check={() => []}
      variables={() => ({})}
      canComplete={false}
    >
      <label className="field field--number">
        Workpack number
        <input value={workpackNumber} readOnly />
      </label>
      {board.length === 0 ? (
        <p>No time sheets for this Work Pack</p>
      ) : (
        <table className="sign-table" aria-label="Hours by Worker, phase and day">
          <thead>
            <tr>
              <th scope="col">Worker and phase</th>
              {days.map((d) => (
                <th scope="col" key={d}>
                  {showDate(d)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {board.flatMap((t) =>
              t.submissionStatus === 'Cancelled'
                ? [
                    <tr key={t.timesheetId}>
                      <td colSpan={days.length + 1}>{`${t.employeeRef}: time sheet Cancelled, no hours can be booked`}</td>
                    </tr>,
                  ]
                : t.phases.map((phase) => (
                    <tr key={`${t.timesheetId}-${phase}`}>
                      <th scope="row">{`${t.employeeRef} ${phase}`}</th>
                      {days.map((d) => {
                        const existing = lineOf(t, phase, d);
                        return (
                          <td key={d}>
                            <Button aria-label={`${t.employeeRef} ${phase} ${showDate(d)}`} onClick={() => select(t, phase, d)}>
                              {existing ? existing.hours : '+'}
                            </Button>
                          </td>
                        );
                      })}
                    </tr>
                  )),
            )}
          </tbody>
        </table>
      )}
      {sheet && selected && (
        <section aria-label="Hour line" className="context-panel">
          <h3>{`${sheet.employeeRef} ${selected.phase} ${showDate(selected.date)}`}</h3>
          <ErrorMessage messages={messages} />
          {!editable && line && <p className="notice">{`Line is not editable (status ${line.status})`}</p>}
          <Field label="Hours" className="field--number">
            <input inputMode="decimal" value={hours} readOnly={!editable} onChange={(e) => setHours(e.target.value)} />
          </Field>
          <Field label="Remarks">
            <input value={remarks} readOnly={!editable} onChange={(e) => setRemarks(e.target.value)} />
          </Field>
          <Field label="Reported completion %" className="field--number">
            <input inputMode="decimal" value={completion} readOnly={!editable} onChange={(e) => setCompletion(e.target.value)} />
          </Field>
          {editable && (
            <Button className="button--primary" onClick={save}>
              Save line
            </Button>
          )}
          {line?.status === 'Draft' && !confirming && <Button onClick={() => setConfirming(true)}>Delete line</Button>}
          {line?.status === 'Draft' && confirming && (
            <p role="group" aria-label="Confirm delete">
              Delete this line?
              <Button onClick={remove}>Yes, delete</Button>
              <Button onClick={() => setConfirming(false)}>No, keep it</Button>
            </p>
          )}
        </section>
      )}
      <TimesheetFileUpload api={api} onSaved={() => void queryClient.invalidateQueries({ queryKey: ['timesheet-lines'] })} />
      {onClose && <Button onClick={onClose}>Back to my tasks</Button>}
    </TaskScreenShell>
  );
}
