import type { ButtonHTMLAttributes } from 'react';

/** A plain <button> (ADR 0017: no UI library, no styling yet). type defaults to "button", as every non-submit button had. */
export function Button({ type = 'button', ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type={type} {...props} />;
}
