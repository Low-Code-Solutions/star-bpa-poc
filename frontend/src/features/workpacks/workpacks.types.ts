import type { StartWorkPackResponse } from '@/types/api';

/** A phase line and a material line as the Create Work Pack screen holds them (man hours as typed). */
export type PhaseRow = { phase: string; manhours: string };
/** id: the saved line's id on the Confirm screen, round-tripped so the save step updates the line in place (BPA-23, A4). */
export type MaterialRow = { id?: string; materialRef: string; phase: string };

/** The Create Work Pack screen's fields; names as the BPA-26 write allow-list for UserTask_CreateWorkPack. */
export type CreateWorkPackForm = {
  activityRef: string;
  drawingRef: string;
  departmentRef: string;
  completionDate: string;
  phases: PhaseRow[];
  materials: MaterialRow[];
};

/** POST /api/v1/workpacks: the started instance, or the HTTP status of the refusal (403 outside time-method-analyst). */
/** The started instance, or the refusal: its status and the contract's problem `detail` (BPA-78: 403 "Project PRJ-142 is not assigned to you"). */
export type StartResult = StartWorkPackResponse | { status: number; detail?: string };
