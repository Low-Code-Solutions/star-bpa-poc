/**
 * App-level configuration (ADR 0017). Nothing here comes from the environment yet, so there is no .env.example: the API is
 * same-origin (the Vite proxy locally, BPA-43 Q11).
 */
export const appConfig = {
  /** Business dates are Asia/Riyadh dates whatever the browser's zone (09 S9, D28, ADR 0013). */
  businessZone: 'Asia/Riyadh',
  /**
   * Waiting for Camunda's task search, which is eventually consistent (BPA-25): pause between reads and how many reads.
   * BPA-6's values; the task list keeps them (moving this polling into TanStack Query is a later clean-up, ADR 0017).
   */
  taskPolling: { retryMs: 500, attempts: 10 },
} as const;
