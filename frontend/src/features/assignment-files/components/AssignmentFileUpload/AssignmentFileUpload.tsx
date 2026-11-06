import { useState } from 'react';
import { Button } from '@/components/common/Button';
import { Field } from '@/components/common/Field';
import type { Api } from '@/services/apiClient';
import type { AssignmentFileResult } from '@/types/api';
import { saveBlob } from '@/utils/download';
import {
  downloadAssignmentFileResultCsv,
  downloadAssignmentTemplate,
  getAssignmentFileResult,
  uploadAssignmentFile,
} from '../../services/assignmentFileService';

const GROUP = 'production-team';

/**
 * BPA-12: the "Assignment file" section on the home page for the Production Team (D58; there is no user task for the upload, so no
 * task screen). The user downloads the template, fills it in and uploads it; the result is every row with its outcome, or one
 * file-level line (D49). Rows read "Pending" until the row validation (BPA-22) sets their outcome; "Refresh result" reads them again.
 * The result can be downloaded as CSV. The field check is the contract's 400 text, "File is required"; every other text comes from the
 * backend. Only the production-team group sees it; the backend enforces the same (403, 09 S6).
 */
export function AssignmentFileUpload({ api, groups }: { api: Api; groups: string[] }) {
  if (!groups.includes(GROUP)) {
    return null;
  }
  return <UploadSection api={api} />;
}

/** What to tell the user when an upload is refused: the contract's own text for a 400, a plain reason for 403 and 413, else a general one. */
function refusal(status: number, detail?: string): string {
  if (status === 400 && detail) {
    return detail;
  }
  if (status === 403) {
    return 'You are not allowed to upload an assignment file';
  }
  if (status === 413) {
    return 'The file is too large (at most 5 MB)';
  }
  return 'The file could not be uploaded';
}

function UploadSection({ api }: { api: Api }) {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<AssignmentFileResult | null>(null);
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
      const uploaded = await uploadAssignmentFile(api, file);
      if (uploaded.ok) {
        setResult(uploaded.result);
      } else {
        setProblem(refusal(uploaded.status, uploaded.detail));
      }
    } finally {
      setBusy(false);
    }
  }

  async function refresh() {
    if (!result) {
      return;
    }
    setProblem(null);
    const latest = await getAssignmentFileResult(api, result.id);
    if (latest) {
      setResult(latest);
    } else {
      setProblem('The result could not be loaded');
    }
  }

  async function downloadResult() {
    if (!result) {
      return;
    }
    setProblem(null);
    const csv = await downloadAssignmentFileResultCsv(api, result.id);
    if (csv) {
      saveBlob(csv, `${result.fileName.replace(/\.xlsx$/i, '')}-result.csv`);
    } else {
      setProblem('The result could not be downloaded');
    }
  }

  async function downloadTemplate() {
    setProblem(null);
    const template = await downloadAssignmentTemplate(api);
    if (template) {
      saveBlob(template, 'worker-assignment-template.xlsx');
    } else {
      setProblem('The template could not be downloaded');
    }
  }

  return (
    <section aria-label="Assignment file upload" className="assignment-file">
      <h2>Assignment file</h2>
      <div className="assignment-file__actions">
        <Button onClick={() => void downloadTemplate()}>Download template</Button>
      </div>
      <Field label="Assignment file">
        <input type="file" accept=".xlsx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </Field>
      <div className="assignment-file__actions">
        <Button className="button--primary" disabled={busy} onClick={() => void upload()}>
          Upload
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
                  <th scope="col">Worker name</th>
                  <th scope="col">Outcome</th>
                </tr>
              </thead>
              <tbody>
                {result.rows.map((row, i) => (
                  <tr key={`${row.rowNumber ?? 'x'}-${i}`}>
                    <td>{row.rowNumber ?? ''}</td>
                    <td>{row.workpackNumber ?? ''}</td>
                    <td>{row.badgeNo ?? ''}</td>
                    <td>{row.workerName ?? ''}</td>
                    <td>{row.wpOutcome ? `${row.outcome ?? ''} (${row.wpOutcome})` : (row.outcome ?? '')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="assignment-file__actions">
            <Button onClick={() => void refresh()}>Refresh result</Button>
            <Button onClick={() => void downloadResult()}>Download result (CSV)</Button>
          </div>
        </div>
      )}
    </section>
  );
}
