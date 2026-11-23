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

/** The days from start to end inclusive as YYYY-MM-DD, without going through a time zone (BPA-50: the open week up to today). */
export function daysFrom(start: string, end: string): string[] {
  const [sy, sm, sd] = start.split('-').map(Number);
  const [ey, em, ed] = end.split('-').map(Number);
  const days: string[] = [];
  const last = Date.UTC(ey, em - 1, ed);
  for (let t = Date.UTC(sy, sm - 1, sd); t <= last; t += 86_400_000) {
    days.push(new Date(t).toISOString().slice(0, 10));
  }
  return days;
}

/**
 * A moment (an ISO instant such as 2026-11-04T22:30:00Z) as its Asia/Riyadh business date, 05 Nov 2026 (D28, 09 S9): the backend answers in UTC, and the
 * business date is the Riyadh one whatever the browser's zone.
 */
export function showInstantDate(instant?: string): string {
  if (!instant) {
    return '';
  }
  const iso = new Intl.DateTimeFormat('en-CA', {
    timeZone: appConfig.businessZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(instant));
  return showDate(iso);
}
