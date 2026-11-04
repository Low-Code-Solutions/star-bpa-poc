import type { Department, PickListProps } from '../../reference-data.types';

/** Departments as "Fabrication <name>", with an empty first option. */
export function DepartmentSelect({ departments, value, onChange, ...rest }: PickListProps & { departments: Department[] }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      <option value="" />
      {departments.map((d) => (
        <option key={d.departmentRef} value={d.departmentRef}>{`${d.departmentRef} ${d.name}`}</option>
      ))}
    </select>
  );
}
