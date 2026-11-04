import { ConfirmWorkPack } from '@/features/workpacks/components/ConfirmWorkPack';
import { CreateWorkPack } from '@/features/workpacks/components/CreateWorkPack';
import { USER_TASKS } from '@/utils/constants';
import type { TaskScreen } from './tasks.types';

/**
 * The screen for each user task, by BPMN element ID. The task list opens only tasks listed here; the others stay disabled
 * until their story adds a screen (BPA-9 Sign off, BPA-11 Schedule). Add new screens here.
 */
const screens: Record<string, TaskScreen> = {
  [USER_TASKS.createWorkPack]: CreateWorkPack,
  [USER_TASKS.confirmWorkPack]: ConfirmWorkPack,
};

/** The screen for this element ID, or undefined. Own keys only, so a name like "constructor" is never a screen. */
export function screenFor(elementId: string): TaskScreen | undefined {
  return Object.hasOwn(screens, elementId) ? screens[elementId] : undefined;
}
