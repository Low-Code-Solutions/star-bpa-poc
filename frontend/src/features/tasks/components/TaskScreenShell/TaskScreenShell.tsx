import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Button } from '@/components/common/Button';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import type { Api } from '@/services/apiClient';
import { useCompleteTask } from '../../hooks/useCompleteTask';
import { useTask } from '../../hooks/useTask';
import type { Task } from '../../tasks.types';

type Props = {
  api: Api;
  taskKey: string;
  /** The form's accessible name and its heading, e.g. "Create Work Pack". */
  title: string;
  onDone: () => void;
  /** Once per task key, when the task arrives: prefill the screen from task.variables (read allow-list, BPA-26). */
  onLoaded?: (task: Task) => void;
  /** The screen's field checks (display rules; S14, ADR 0004). Any message blocks the completion. */
  check: () => string[];
  /** The variables to complete the task with; names must be on the task's write allow-list (bpa.tasks, BPA-26). */
  variables: () => Record<string, unknown>;
  /**
   * False for a screen that saves through its own endpoints and does not complete the task (none today; Book hours, BPA-50, did until BPA-53 made it
   * "Send to Timekeeper"). No completion button then, and submitting the form (Enter in a field) does nothing.
   */
  canComplete?: boolean;
  /** The completion button's text; "Complete task" unless the task means something more specific (Book hours: "Send to Timekeeper", BPA-53). */
  completeLabel?: string;
  children: ReactNode;
};

/**
 * The shared frame of every task screen (ADR 0017): loads the task, shows its validationMessages (the save step's BPMN
 * error, G2), blocks on the screen's field checks, completes the task through the task API and shows the contract's error
 * text. Renders: form[aria-label] > h2, the alert list, the screen's fields, and the "Complete task" button last.
 */
export function TaskScreenShell({
  api,
  taskKey,
  title,
  onDone,
  onLoaded,
  check,
  variables,
  canComplete = true,
  completeLabel = 'Complete task',
  children,
}: Props) {
  const { data: task } = useTask(api, taskKey);
  const complete = useCompleteTask(api);
  const [messages, setMessages] = useState<string[]>([]);
  const loadedKey = useRef<string | null>(null);
  const onLoadedRef = useRef(onLoaded);
  onLoadedRef.current = onLoaded;

  useEffect(() => {
    if (task && loadedKey.current !== task.key) {
      loadedKey.current = task.key;
      setMessages(task.validationMessages ?? []);
      onLoadedRef.current?.(task);
    }
  }, [task]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canComplete) {
      return;
    }
    const blocked = check();
    setMessages(blocked);
    if (blocked.length > 0) {
      return;
    }
    const result = await complete(taskKey, variables());
    if (result.ok) {
      onDone();
    } else {
      setMessages([result.detail ?? `The task could not be completed (${result.status})`]);
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-label={title} className="task-sheet">
      <h2 className="task-tag">{title}</h2>
      <ErrorMessage messages={messages} />
      {children}
      {canComplete && (
        <Button type="submit" className="button--primary">
          {completeLabel}
        </Button>
      )}
    </form>
  );
}
