import type { ButtonHTMLAttributes } from 'react';

/** A plain <button> with the portal's button style (styles/global.css). type defaults to "button", as every non-submit button had. */
export function Button({ type = 'button', className, ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type={type} className={className ? `button ${className}` : 'button'} {...props} />;
}
