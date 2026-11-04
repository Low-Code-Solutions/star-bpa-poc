import { useCallback, useEffect, useState } from 'react';
import type { apiClient } from './api/client';
import type { components } from './api/schema';
import { CreateWorkPack } from './CreateWorkPack';

type Api = ReturnType<typeof apiClient>;
type TaskSummary = components['schemas']['TaskSummary'];

/** Pause between task-list reads while waiting for Camunda's search (BPA-25: eventually consistent). Tests shorten it. */
export let RETRY_MS = 500;
export function setRetryMs(ms: number) {
  RETRY_MS = ms;
}

/**
 * The user's open tasks through the backend task API (S14; BPA-26), and for the Time & Method Analyst the Work Pack request
 * start (BPA-6; D52). Screens for the other user tasks come with their stories (BPA-8/23, 9, 11).
 */
export function TaskList({ api, groups }: { api: Api; groups: string[] }) {
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [open, setOpen] = useState<TaskSummary | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const { data } = await api.GET('/api/v1/tasks');
    setTasks(Array.isArray(data) ? data : []);
    return Array.isArray(data) ? data : [];
  }, [api]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function startRequest() {
    const { data, response } = await api.POST('/api/v1/workpacks');
    if (!response.ok || !data) {
      setNotice(`The Work Pack request could not be started (${response.status})`);
      return;
    }
    // The new Create Work Pack task reaches the task search a moment later (BPA-25: eventually consistent).
    for (let attempt = 0; attempt < 10; attempt++) {
      const list = await refresh();
      const created = list.find((t) => t.processInstanceKey === data.processInstanceKey && t.elementId === 'UserTask_CreateWorkPack');
      if (created) {
        setOpen(created);
        setNotice(null);
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, RETRY_MS));
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
    for (let attempt = 0; attempt < 10; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, RETRY_MS));
      const list = await refresh();
      const back = list.find((t) => t.processInstanceKey === done.processInstanceKey && t.elementId === done.elementId && t.key !== done.key);
      if (back) {
        setNotice(null);
        setOpen(back);
        return;
      }
    }
    // ASSUMPTION: no returned task within ~5 s means the save step accepted the data.
    setNotice('Task completed; the Work Pack is saved');
  }

  if (open?.elementId === 'UserTask_CreateWorkPack') {
    return <CreateWorkPack key={open.key} api={api} taskKey={open.key} onDone={() => awaitSaveOutcome(open)} />;
  }

  return (
    <section aria-label="My tasks">
      <h2>My tasks</h2>
      {groups.includes('time-method-analyst') && (
        <button type="button" onClick={startRequest}>Start Work Pack request</button>
      )}
      {notice && <p role="status">{notice}</p>}
      {tasks.length === 0 ? (
        <p>No open tasks</p>
      ) : (
        <ul>
          {tasks.map((t) => (
            <li key={t.key}>
              <button type="button" onClick={() => setOpen(t)} disabled={t.elementId !== 'UserTask_CreateWorkPack'}>
                {t.name}{t.workpackNumber ? ` ${t.workpackNumber}` : ''}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
