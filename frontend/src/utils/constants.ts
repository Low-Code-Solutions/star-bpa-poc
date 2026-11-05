/** Work Pack phases, FRD-7 §7.4. */
export const PHASES = ['Engineering', 'Cutting', 'Welding', 'Finishing'];

/** BPMN element IDs of the user tasks the portal has a screen for (Process_PPC_WorkPackAssignment). */
export const USER_TASKS = {
  createWorkPack: 'UserTask_CreateWorkPack',
  confirmWorkPack: 'UserTask_ConfirmWorkPack',
  signOffRelease: 'UserTask_SignOffRelease',
  scheduleWorkPack: 'UserTask_ScheduleWorkPack',
} as const;
