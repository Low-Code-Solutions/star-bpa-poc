import type { ReactNode } from 'react';

/** A label wrapping its text and its control, as the screens had: <label>Text<select …/></label>. */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label>
      {label}
      {children}
    </label>
  );
}
