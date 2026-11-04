import type { Drawing, PickListProps } from '../../reference-data.types';

/** Drawings as "DWG-118 <title>", with an empty first option. */
export function DrawingSelect({ drawings, value, onChange, ...rest }: PickListProps & { drawings: Drawing[] }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      <option value="" />
      {drawings.map((d) => (
        <option key={d.drawingRef} value={d.drawingRef}>{`${d.drawingRef} ${d.title}`}</option>
      ))}
    </select>
  );
}
