import type { ProjectMasterData } from '@/types/api';

/** The outcome of reading one project's master data: the data, or the status and the contract's problem `detail` (BPA-77: 403 "Project PRJ-142 is not assigned to you"). */
export type MasterDataResult = { ok: true; data: ProjectMasterData } | { ok: false; status: number; detail?: string };
