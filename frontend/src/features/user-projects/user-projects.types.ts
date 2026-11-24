/** The outcome of an assign or unassign: ok, or the status and the contract's problem `detail` (BPA-76). */
export type ChangeResult = { ok: true } | { ok: false; status: number; detail?: string };
