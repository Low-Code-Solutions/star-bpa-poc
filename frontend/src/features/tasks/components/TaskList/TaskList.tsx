import { useState } from 'react';
import { Button } from '@/components/common/Button';
import { appConfig } from '@/config/app.config';
import { useStartWorkPack } from '@/features/workpacks/hooks/useStartWorkPack';
import type { Api } from '@/services/apiClient';
import { USER_TASKS } from '@/utils/constants';
import { useTasks } from '../../hooks/useTasks';
import { taskScreens } from '../../taskScreens';
import type { TaskSummary } from '../../tasks.types';

/** Pause between task-list reads while waiting for Camunda's search (BPA-25: eventually consistent). Tests shorten it. */
export let RETRY_MS: number = appConfig.taskPolling.retryMs;
export function setRetryMs(ms: number) {
  RETRY_MS = ms;
}

const pause = () => new Promise((resolve) => setTimeout(resolve, RETRY_MS));

/**
 * The user's open tasks through the backend task API (S14; BPA-26), and for the Time & Method Analyst the Work Pack request
 * start (BPA-6; D52). A task opens in place on its screen from taskScreens.ts; tasks without a screen stay disabled.
 */
export function TaskList({ api, groups }: { api: Api; groups: string[] }) {
  const { data: tasks = [], refetch } = useTasks(api);
  const start = useStartWorkPack(api);
  const [open, setOpen] = useState<TaskSummary | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = async () => (await refetch()).data ?? [];

  async function startRequest() {
    const started = await start();
    if (!('processInstanceKey' in started)) {
      setNotice(`The Work Pack request could not be started (${started.status})`);
      return;
    }
    // The new Create Work Pack task reaches the task search a moment later (BPA-25: eventually consistent).
    for (let attempt = 0; attempt < appConfig.taskPolling.attempts; attempt++) {
      const list = await refresh();
      const created = list.find(
        (t) => t.processInstanceKey === started.processInstanceKey && t.elementId === USER_TASKS.createWorkPack,
      );
      if (created) {
        setOpen(created);
        setNotice(null);
        return;
      }
      await pause();
    }
    setNotice('The Work Pack request was started; its task will appear in the list shortly');
  }

  /**
   * After a completion the save step either saves the Work Pack or sends the task back with its messages (BPMN error, G2).
   * The returned task (a new key on the same instance) reaches the search a moment later, so watch for it and reopen it.
   */
  async function awaitSaveOutcome(done: TaskSummary) {
    setOpen(null);
    setNotice('Task completed; checking the save');
    for (let attempt = 0; attempt < appConfig.taskPolling.attempts; attempt++) {
      await pause();
      const list = await refresh();
      const back = list.find(
        (t) => t.processInstanceKey === done.processInstanceKey && t.elementId === done.elementId && t.key !== done.key,
      );
      if (back) {
        setNotice(null);
        setOpen(back);
        return;
      }
    }
    // ASSUMPTION: no returned task within ~5 s means the save step accepted the data.
    setNotice('Task completed; the Work Pack is saved');
  }

  const Screen = open ? taskScreens[open.elementId] : undefined;
  if (open && Screen) {
    return <Screen key={open.key} api={api} taskKey={open.key} onDone={() => awaitSaveOutcome(open)} />;
  }

  return (
    <section aria-label="My tasks">
      <h2>My tasks</h2>
      {groups.includes('time-method-analyst') && <Button onClick={startRequest}>Start Work Pack request</Button>}
      {notice && <p role="status">{notice}</p>}
      {tasks.length === 0 ? (
        <p>No open tasks</p>
      ) : (
        <ul>
          {tasks.map((t) => (
            <li key={t.key}>
              <Button onClick={() => setOpen(t)} disabled={!taskScreens[t.elementId]}>
                {t.name}
                {t.workpackNumber ? ` ${t.workpackNumber}` : ''}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
