import { useNavigate } from 'react-router';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Spinner } from '@/components/common/Spinner';
import type { Api } from '@/services/apiClient';
import { useWorkPackOpenTasks } from '../../hooks/useWorkPackOpenTasks';

const age = (iso: string) => {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
  return days <= 0 ? 'open today' : `open ${days} day${days === 1 ? '' : 's'}`;
};

/**
 * BPA-73: the open user tasks on this Work Pack, from every group, as a side panel. A task of one of the user's own groups is a button that opens it on the
 * Home page; the others are listed, for their group, with nothing to open them with (the backend answers 403 to anyone else anyway). If the process engine
 * cannot be read, only this panel says so: the rest of the page does not depend on it.
 */
export function OpenTasksPanel({ api, workpackNumber }: { api: Api; workpackNumber: string }) {
  const navigate = useNavigate();
  const { data: tasks } = useWorkPackOpenTasks(api, workpackNumber);
  return (
    <aside aria-label="Open tasks" className="open-tasks">
      <h3>Open tasks</h3>
      {tasks === undefined ? <Spinner /> : null}
      {tasks === null ? <ErrorMessage text="The open tasks could not be read" /> : null}
      {tasks && tasks.length === 0 ? <p>No open tasks</p> : null}
      {tasks && tasks.length > 0 ? (
        <ul className="register">
          {tasks.map((t) => (
            <li key={t.key}>
              {t.canOpen ? (
                <button type="button" onClick={() => navigate('/', { state: { taskKey: t.key } })}>
                  Open {t.name}
                </button>
              ) : (
                <span>{t.name}</span>
              )}{' '}
              <small>
                {t.canOpen ? '' : `for ${t.candidateGroups.join(', ')}, `}
                {age(t.creationDate)}
              </small>
            </li>
          ))}
        </ul>
      ) : null}
    </aside>
  );
}
