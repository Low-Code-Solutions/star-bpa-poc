import type { Material, PickListProps } from '../../reference-data.types';

/** Materials as "MAT-PIPE-06 <description>", with an empty first option. */
export function MaterialSelect({ materials, value, onChange, ...rest }: PickListProps & { materials: Material[] }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      <option value="" />
      {materials.map((m) => (
        <option key={m.materialRef} value={m.materialRef}>{`${m.materialRef} ${m.description}`}</option>
      ))}
    </select>
  );
}
