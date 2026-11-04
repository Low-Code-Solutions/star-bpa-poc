import type { ReactNode } from 'react';

/** A label wrapping its text and its control, as the screens had: <label>Text<select …/></label>. */
export function Field({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return (
    <label className={className ? `field ${className}` : 'field'}>
      {label}
      {children}
    </label>
  );
}
