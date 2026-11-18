import { Button } from '@/components/common/Button';
import type { HoursSubmissionLine } from '@/types/api';
import { showDate } from '@/utils/dates';

/**
 * The lines of a submission of hours, read-only (BPA-53, BPA-56): Badge No, date, phase, hours and remarks. With {@code onAmend} each row gets an
 * "Amend" button (the Timekeeper, BPA-53); without it the table has no Amend column (the Production Supervisor does not edit hours, BPA-56).
 */
export function SubmissionLines({
  lines,
  onAmend,
}: {
  lines: HoursSubmissionLine[];
  onAmend?: (line: HoursSubmissionLine) => void;
}) {
  return (
    <table className="sign-table" aria-label="Hours sent">
      <thead>
        <tr>
          <th scope="col">Badge No</th>
          <th scope="col">Work date</th>
          <th scope="col">Phase</th>
          <th scope="col">Hours</th>
          <th scope="col">Remarks</th>
          {onAmend && <th scope="col">Amend</th>}
        </tr>
      </thead>
      <tbody>
        {lines.map((l) => (
          <tr key={l.id}>
            <td>{l.badgeNo}</td>
            <td>{showDate(l.workDate)}</td>
            <td>{l.phase}</td>
            <td>{l.hours}</td>
            <td>{l.remarks ?? ''}</td>
            {onAmend && (
              <td>
                <Button aria-label={`Amend ${l.badgeNo} ${showDate(l.workDate)} ${l.phase}`} onClick={() => onAmend(l)}>
                  Amend
                </Button>
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
