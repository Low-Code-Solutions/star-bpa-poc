import type { Activity, PickListProps } from '../../reference-data.types';

/** Activities as "A-2201 <name>", with an empty first option. */
export function ActivitySelect({ activities, value, onChange, ...rest }: PickListProps & { activities: Activity[] }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      <option value="" />
      {activities.map((a) => (
        <option key={a.activityRef} value={a.activityRef}>{`${a.activityRef} ${a.activityName}`}</option>
      ))}
    </select>
  );
}
