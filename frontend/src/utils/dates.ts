import { appConfig } from '@/config/app.config';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Today in Asia/Riyadh as YYYY-MM-DD (D28, 09 S9): business dates are Riyadh dates whatever the browser's zone. */
export function todayInRiyadh(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: appConfig.businessZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

/** 2026-11-01 -> 01 Nov 2026, without going through a time zone. */
export function showDate(iso?: string): string {
  if (!iso) {
    return '';
  }
  const [y, m, d] = iso.split('-');
  return `${d} ${MONTHS[Number(m) - 1]} ${y}`;
}
