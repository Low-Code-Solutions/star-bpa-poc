import { CreateWorkPack } from '@/features/workpacks/components/CreateWorkPack';
import { USER_TASKS } from '@/utils/constants';
import type { TaskScreen } from './tasks.types';

/**
 * The screen for each user task, by BPMN element ID. The task list opens only tasks listed here; the others stay disabled
 * until their story adds a screen (BPA-8/23 Confirm, BPA-9 Sign off, BPA-11 Schedule). Add new screens here.
 */
export const taskScreens: Record<string, TaskScreen> = {
  [USER_TASKS.createWorkPack]: CreateWorkPack,
};
