import type { components } from '@/services/schema';

// Names for the generated contract types (api/openapi.yaml via schema.d.ts). Use these; never retype a schema by hand.
type Schemas = components['schemas'];

export type Me = Schemas['Me'];
export type TaskSummary = Schemas['TaskSummary'];
export type Task = Schemas['Task'];
export type LookAhead = Schemas['LookAhead'];
export type WorkPackPrintSummary = Schemas['WorkPackPrintSummary'];
export type AssignmentFileResult = Schemas['AssignmentFileResult'];
export type AssignmentFileRow = Schemas['AssignmentFileRow'];
export type Activity = Schemas['Activity'];
export type Drawing = Schemas['Drawing'];
export type Material = Schemas['Material'];
export type Department = Schemas['Department'];
export type StartWorkPackResponse = Schemas['StartWorkPackResponse'];
export type DigitalTimesheet = Schemas['DigitalTimesheet'];
export type DigitalTimesheetLine = Schemas['DigitalTimesheetLine'];
export type TimesheetLines = Schemas['TimesheetLines'];
export type NewDigitalTimesheetLine = Schemas['NewDigitalTimesheetLine'];
export type DigitalTimesheetLineChange = Schemas['DigitalTimesheetLineChange'];
export type TimesheetFileResult = Schemas['TimesheetFileResult'];
export type TimesheetFileRow = Schemas['TimesheetFileRow'];
export type CompletionReportResult = Schemas['CompletionReportResult'];
export type ProgressWeek = Schemas['ProgressWeek'];
export type WorkpackProgressRow = Schemas['WorkpackProgressRow'];
export type CbsActualsWeek = Schemas['CbsActualsWeek'];
export type CbsActualRow = Schemas['CbsActualRow'];
export type VarianceAlert = Schemas['VarianceAlert'];
export type UnsentLine = Schemas['UnsentLine'];
export type EarnedManhoursWeek = Schemas['EarnedManhoursWeek'];
export type EarnedManhoursRow = Schemas['EarnedManhoursRow'];
export type HoursSubmission = Schemas['HoursSubmission'];
export type HoursSubmissionLine = Schemas['HoursSubmissionLine'];
export type LineAmendment = Schemas['LineAmendment'];
