import { useState } from 'react';
import { Field } from '@/components/common/Field';
import { TaskScreenShell } from '@/features/tasks/components/TaskScreenShell';
import type { Task, TaskScreenProps } from '@/features/tasks/tasks.types';
import { saveBlob } from '@/utils/download';
import { fmsHandOffMessages, type FmsChoice } from '../../fmsHandOffMessages';
import { downloadFmsHoursExport } from '../../services/fmsHoursExportService';

type FmsFile = { id: string; periodMonth: string; rowCount: number };

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];
const SHORT_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-11-01" as "November 2026". */
function monthLabel(periodMonth: string): string {
  const [year, month] = periodMonth.split('-');
  return `${MONTHS[Number(month) - 1] ?? month} ${year}`;
}

/** "2026-11-19" as "19 Nov 2026". */
function dateLabel(date: string): string {
  const [year, month, day] = date.split('-');
  return `${Number(day)} ${SHORT_MONTHS[Number(month) - 1] ?? month} ${year}`;
}

function isFile(value: unknown): value is FmsFile {
  const v = value as Partial<FmsFile> | null;
  return (
    typeof v === 'object' &&
    v !== null &&
    typeof v.id === 'string' &&
    typeof v.periodMonth === 'string' &&
    typeof v.rowCount === 'number'
  );
}

/**
 * BPA-58: the Send hours to FMS screen on the shared TaskScreenShell (UserTask_SendHoursToFms, group ppc-team). It lists the week's FMS hours files, one
 * per calendar month (S5), each with a Download (the stored file, never rebuilt) and one outcome: Sent to FMS, or Rejected by FMS with a reason of at most
 * 250 characters. PPC uploads the file to FMS by hand. Every file needs an outcome; the checks use the backend's texts (TaskValidity re-checks them) and
 * the variable is fmsHandOffs, one entry per file (bpa.tasks write allow-list). The labels are ASSUMPTIONs.
 */
export function SendHoursToFms({ api, taskKey, onDone }: TaskScreenProps) {
  const [weekEnding, setWeekEnding] = useState('');
  const [files, setFiles] = useState<FmsFile[]>([]);
  const [choices, setChoices] = useState<Record<string, FmsChoice>>({});
  const [downloadFailed, setDownloadFailed] = useState(false);

  function loaded(task: Task) {
    const v = (task.variables ?? {}) as Record<string, unknown>;
    setWeekEnding(typeof v.weekEnding === 'string' ? v.weekEnding : '');
    setFiles(Array.isArray(v.fmsHoursExports) ? v.fmsHoursExports.filter(isFile) : []);
  }

  const choiceOf = (id: string): FmsChoice => choices[id] ?? { outcome: '', reason: '' };
  const setChoice = (id: string, change: Partial<FmsChoice>) =>
    setChoices((all) => ({ ...all, [id]: { ...choiceOf(id), ...change } }));

  async function download(file: FmsFile) {
    setDownloadFailed(false);
    const blob = await downloadFmsHoursExport(api, file.id);
    if (blob) {
      saveBlob(blob, `fms-hours-${weekEnding}-${file.periodMonth.slice(0, 7)}.xlsx`);
    } else {
      setDownloadFailed(true);
    }
  }

  return (
    <TaskScreenShell
      api={api}
      taskKey={taskKey}
      title="Send hours to FMS"
      onDone={onDone}
      onLoaded={loaded}
      check={() => fmsHandOffMessages(files.map((f) => choiceOf(f.id)))}
      variables={() => ({
        fmsHandOffs: files.map((f) => {
          const c = choiceOf(f.id);
          return c.outcome === 'Rejected by FMS'
            ? { fmsHoursExportId: f.id, outcome: c.outcome, reason: c.reason.trim() }
            : { fmsHoursExportId: f.id, outcome: c.outcome };
        }),
      })}
    >
      {weekEnding !== '' && <p>{`Week ending ${dateLabel(weekEnding)}`}</p>}
      {downloadFailed && <p role="alert">The file could not be downloaded</p>}
      {files.map((file) => {
        const choice = choiceOf(file.id);
        return (
          <fieldset key={file.id} className="choices">
            <legend>{monthLabel(file.periodMonth)}</legend>
            <p>{file.rowCount === 1 ? '1 row' : `${file.rowCount} rows`}</p>
            <button type="button" onClick={() => void download(file)}>
              Download
            </button>
            {(['Sent to FMS', 'Rejected by FMS'] as const).map((outcome) => (
              <label key={outcome}>
                <input
                  type="radio"
                  name={`fmsOutcome-${file.id}`}
                  value={outcome}
                  checked={choice.outcome === outcome}
                  onChange={() => setChoice(file.id, { outcome })}
                />
                {outcome}
              </label>
            ))}
            {choice.outcome === 'Rejected by FMS' && (
              <Field label="Reason">
                <textarea value={choice.reason} onChange={(e) => setChoice(file.id, { reason: e.target.value })} rows={3} />
              </Field>
            )}
          </fieldset>
        );
      })}
    </TaskScreenShell>
  );
}
