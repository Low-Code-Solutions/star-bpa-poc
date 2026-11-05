import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/common/Button';
import type { Api } from '@/services/apiClient';
import { PRINT_GROUPS } from '@/utils/constants';
import { showDate } from '@/utils/dates';
import { useWorkPackPrints } from '../../hooks/useWorkPackPrints';
import { getWorkPackPrint } from '../../services/workpackService';

type Shown = { workpackNumber: string; url: string };

/**
 * BPA-10: the "Workpack prints" list on the home page (the print has no task screen). It lists the Work Packs that have a print, newest
 * first, each with a "Print PDF" button: the PDF is fetched as a blob (GET /api/v1/workpacks/{n}/print) and shown in an iframe, which
 * is the browser's PDF viewer (09 S4, ADR 0004). Only the groups allowed to read a print see it; the backend enforces the same (403).
 */
export function WorkPackPrints({ api, groups }: { api: Api; groups: string[] }) {
  if (!groups.some((group) => PRINT_GROUPS.includes(group))) {
    return null;
  }
  return <PrintsSection api={api} />;
}

function PrintsSection({ api }: { api: Api }) {
  const { prints, refresh } = useWorkPackPrints(api);
  const [shown, setShown] = useState<Shown | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const current = useRef<string | null>(null);

  // The blob URL of the print on show is released when another replaces it and when the list goes away.
  useEffect(
    () => () => {
      if (current.current) {
        URL.revokeObjectURL(current.current);
      }
    },
    [],
  );

  async function open(workpackNumber: string) {
    setProblem(null);
    const result = await getWorkPackPrint(api, workpackNumber);
    if (!result.ok) {
      setShown(null);
      setProblem(result.status === 404 ? (result.detail ?? 'Workpack print not available') : 'The print could not be loaded');
      return;
    }
    if (current.current) {
      URL.revokeObjectURL(current.current);
    }
    current.current = URL.createObjectURL(result.blob);
    setShown({ workpackNumber, url: current.current });
  }

  return (
    <section aria-label="Workpack prints" className="prints">
      <h2>Workpack prints</h2>
      <Button onClick={() => void refresh()}>Refresh</Button>
      {prints === null && <p className="notice notice--error">The list of prints could not be loaded</p>}
      {prints && prints.length === 0 && <p>No Workpack prints yet</p>}
      {prints && prints.length > 0 && (
        <ul className="prints__list">
          {prints.map((p) => (
            <li key={p.workpackNumber} className="prints__row">
              <span className="prints__number">{p.workpackNumber}</span>
              <span>{p.drawingRef}</span>
              <span>{`${showDate(p.plannedStartDate)} to ${showDate(p.plannedEndDate)}`}</span>
              <Button aria-label={`Print PDF ${p.workpackNumber}`} onClick={() => void open(p.workpackNumber)}>
                Print PDF
              </Button>
            </li>
          ))}
        </ul>
      )}
      {problem && (
        <p role="alert" className="notice notice--error">
          {problem}
        </p>
      )}
      {shown && <iframe title={`Workpack print ${shown.workpackNumber}`} src={shown.url} className="prints__frame" />}
    </section>
  );
}
