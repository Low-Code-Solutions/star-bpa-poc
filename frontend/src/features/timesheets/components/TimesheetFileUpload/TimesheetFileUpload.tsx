import { useState } from 'react';
import { Button } from '@/components/common/Button';
import { Field } from '@/components/common/Field';
import type { Api } from '@/services/apiClient';
import type { TimesheetFileResult } from '@/types/api';
import { saveBlob } from '@/utils/download';
import { downloadTimesheetResultCsv, downloadTimesheetTemplate, uploadTimesheetFile } from '../../services/timesheetFileService';

/** What to tell the user when an upload is refused: the backend's own text for a 400 or a 413, a plain reason for 403, else a general one. */
function refusal(status: number, detail?: string): string {
  if ((status === 400 || status === 413) && detail) {
    return detail;
  }
  if (status === 403) {
    return 'You are not allowed to upload a time sheet';
  }
  return 'The file could not be uploaded';
}

/**
 * BPA-52: the "Upload time sheet" section of the Book hours screen (UserTask_BookHours; E2-03b). The user downloads the template, fills it in and uploads it
 * as .xlsx or .csv; the result is every row, as uploaded, with its outcome, or one file-level line (D49 pattern). Rows are checked by the backend exactly as
 * a form entry; valid ones are saved as Draft lines even when others are rejected, so {@code onSaved} tells the grid to read the lines again. The result can
 * be downloaded as CSV. The only field check is the contract's 400 text, "File is required"; every other text comes from the backend. Who may upload is the
 * backend's rule (403, C-E2-5); the section is part of the screen the Supervisor already opens.
 */
export function TimesheetFileUpload({ api, onSaved }: { api: Api; onSaved: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<TimesheetFileResult | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function upload() {
    if (!file) {
      setProblem('File is required');
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const uploaded = await uploadTimesheetFile(api, file);
      if (uploaded.ok) {
        setResult(uploaded.result);
        if (uploaded.result.rows.some((row) => row.outcome === 'Saved')) {
          onSaved();
        }
      } else {
        setProblem(refusal(uploaded.status, uploaded.detail));
        // A failed call (the engine down, 503, or no answer) can come after earlier rows were saved: the grid reads the lines again (BPA-52 review).
        if (uploaded.status === 0 || uploaded.status >= 500) {
          onSaved();
        }
      }
    } finally {
      setBusy(false);
    }
  }

  async function downloadResult() {
    if (!result) {
      return;
    }
    setProblem(null);
    const csv = await downloadTimesheetResultCsv(api, result.id);
    if (csv) {
      saveBlob(csv, `${result.fileName.replace(/\.(xlsx|csv)$/i, '')}-result.csv`);
    } else {
      setProblem('The result could not be downloaded');
    }
  }

  async function downloadTemplate() {
    setProblem(null);
    const template = await downloadTimesheetTemplate(api);
    if (template) {
      saveBlob(template, 'timesheet-upload-template.xlsx');
    } else {
      setProblem('The template could not be downloaded');
    }
  }

  return (
    <section aria-label="Time sheet upload" className="assignment-file">
      <h3>Upload time sheet</h3>
      <div className="assignment-file__actions">
        <Button onClick={() => void downloadTemplate()}>Download template</Button>
      </div>
      <Field label="Time sheet file">
        <input type="file" accept=".xlsx,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </Field>
      <div className="assignment-file__actions">
        <Button className="button--primary" disabled={busy} onClick={() => void upload()}>
          Upload time sheet
        </Button>
      </div>
      {problem && (
        <p role="alert" className="notice notice--error">
          {problem}
        </p>
      )}
      {result && (
        <div className="assignment-file__result">
          {result.outcome ? (
            <p className="notice">{result.outcome}</p>
          ) : (
            <table className="sign-table">
              <caption>{`Result of ${result.fileName}`}</caption>
              <thead>
                <tr>
                  <th scope="col">Row</th>
                  <th scope="col">Workpack number</th>
                  <th scope="col">Badge No</th>
                  <th scope="col">Work date</th>
                  <th scope="col">Phase</th>
                  <th scope="col">Hours</th>
                  <th scope="col">Remarks</th>
                  <th scope="col">Outcome</th>
                </tr>
              </thead>
              <tbody>
                {result.rows.map((row, i) => (
                  <tr key={`${row.rowNumber}-${i}`}>
                    <td>{row.rowNumber}</td>
                    <td>{row.workpackNumber ?? ''}</td>
                    <td>{row.badgeNo ?? ''}</td>
                    <td>{row.workDate ?? ''}</td>
                    <td>{row.phase ?? ''}</td>
                    <td>{row.hours ?? ''}</td>
                    <td>{row.remarks ?? ''}</td>
                    <td>{row.outcome}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="assignment-file__actions">
            <Button onClick={() => void downloadResult()}>Download result (CSV)</Button>
          </div>
        </div>
      )}
    </section>
  );
}
