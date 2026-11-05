import type { components } from '@/services/schema';

// Names for the generated contract types (api/openapi.yaml via schema.d.ts). Use these; never retype a schema by hand.
type Schemas = components['schemas'];

export type Me = Schemas['Me'];
export type TaskSummary = Schemas['TaskSummary'];
export type Task = Schemas['Task'];
export type LookAhead = Schemas['LookAhead'];
export type WorkPackPrintSummary = Schemas['WorkPackPrintSummary'];
export type Activity = Schemas['Activity'];
export type Drawing = Schemas['Drawing'];
export type Material = Schemas['Material'];
export type Department = Schemas['Department'];
export type StartWorkPackResponse = Schemas['StartWorkPackResponse'];
