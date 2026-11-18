/** Work Pack phases, FRD-7 §7.4. */
export const PHASES = ['Engineering', 'Cutting', 'Welding', 'Finishing'];

/** The groups that may read a Workpack print and see the list of prints (BPA-10; the backend enforces it, 09 S6). */
export const PRINT_GROUPS = ['time-method-analyst', 'production-planner', 'production-team'];

/** BPMN element IDs of the user tasks the portal has a screen for (Process_PPC_WorkPackAssignment). */
export const USER_TASKS = {
  createWorkPack: 'UserTask_CreateWorkPack',
  confirmWorkPack: 'UserTask_ConfirmWorkPack',
  signOffRelease: 'UserTask_SignOffRelease',
  scheduleWorkPack: 'UserTask_ScheduleWorkPack',
  // Process_PPC_TimesheetToProgress (Epic 2, BPA-50)
  bookHours: 'UserTask_BookHours',
  // BPA-53
  validateHours: 'UserTask_ValidateHours',
  // BPA-56
  approveHoursProduction: 'UserTask_ApproveHoursProduction',
  // BPA-57
  approveHoursPpc: 'UserTask_ApproveHoursPpc',
} as const;
