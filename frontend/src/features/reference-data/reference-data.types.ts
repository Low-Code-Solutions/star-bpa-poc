export type { Activity, Department, Drawing, Material } from '@/types/api';

/** The props every pick list takes: the value, the change handler and, inside a row without a label, an aria-label. */
export type PickListProps = { value: string; onChange: (value: string) => void; 'aria-label'?: string };
