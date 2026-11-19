import { useState } from 'react';
import { Button } from '@/components/common/Button';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import type { CompletionReportResult } from '@/types/api';
import { showDate } from '@/utils/dates';
import { uploadCompletionReport } from '../../services/completionReportService';

/** What to tell the user when an upload is refused: the backend's own text for a 400 or a 413, a plain reason for 403 and 404, else a general one. */
function refusal(status: number, detail?: string): string {
  if ((status === 400 || status === 413) && detail) {
    return detail;
  }
  if (status === 403) {
    return 'You are not allowed to upload the completion report';
  }
  if (status === 404) {
    return 'This task is no longer open';
  }
  return 'The file could not be uploaded';
}

/**
 * BPA-59: the Upload FMS completion report screen on the shared TaskScreenShell (UserTask_UploadCompletionReport, group ppc-team). PPC uploads the weekly
 * report (.xlsx or .csv) into this task, so it belongs to the task's week; the result is every row, as written, with its outcome, or one file-level line
 * (the BPA-52 pattern). Several uploads may be made: the latest one is what Complete task sends (completionReportId, bpa.tasks write allow-list).
 * The checks here are the contract's 400 "File is required" and "Completion report is required" (an ASSUMPTION text); every other text comes from the
 * backend, which also returns the task here with "No usable rows in the completion report" when the report had no usable row.
 */
export function UploadCompletionReport({ api, taskKey, onDone }: TaskScreenProps) {
  const [weekEnding, setWeekEnding] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<CompletionReportResult | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    setWeekEnding(typeof v.weekEnding === 'string' ? v.weekEnding : '');
  }

  async function upload() {
    if (!file) {
      setProblem('File is required');
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const uploaded = await uploadCompletionReport(api, file, taskKey);
      if (uploaded.ok) {
        setResult(uploaded.result);
      } else {
        setProblem(refusal(uploaded.status, uploaded.detail));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Upload FMS completion report"
      onDone={onDone}
      onLoaded={loaded}
      check={() => (result ? [] : ['Completion report is required'])}
      variables={() => ({ completionReportId: result?.id })}
    >
      {weekEnding !== '' && <p>{`Week ending ${showDate(weekEnding)}`}</p>}
      <section aria-label="Completion report upload" className="assignment-file">
        <Field label="Completion report file">
          <input type="file" accept=".xlsx,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </Field>
        <div className="assignment-file__actions">
          <Button className="button--primary" disabled={busy} onClick={() => void upload()}>
            Upload report
          </Button>
        </div>
        {problem && (
          <p role="alert" className="notice notice--error">
            {problem}
          </p>
        )}
        {result &&
          (result.outcome ? (
            <p className="notice">{result.outcome}</p>
          ) : (
            <table className="sign-table">
              <caption>{`Result of ${result.fileName}`}</caption>
              <thead>
                <tr>
                  <th scope="col">Row</th>
                  <th scope="col">Workpack number</th>
                  <th scope="col">Completion %</th>
                  <th scope="col">Actual hours</th>
                  <th scope="col">Source</th>
                  <th scope="col">Outcome</th>
                </tr>
              </thead>
              <tbody>
                {result.rows.map((row, i) => (
                  <tr key={`${row.rowNumber}-${i}`}>
                    <td>{row.rowNumber}</td>
                    <td>{row.workpackNumber ?? ''}</td>
                    <td>{row.completionPct ?? ''}</td>
                    <td>{row.actualHours ?? ''}</td>
                    <td>{row.source ?? ''}</td>
                    <td>{row.outcome}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
      </section>
    </TaskScreenShell>
  );
}
