import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/common/Button';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Field } from '@/components/common/Field';
import { Spinner } from '@/components/common/Spinner';
import type { Api } from '@/services/apiClient';
import { useProjects } from '../../hooks/useProjects';
import { useUserProjects } from '../../hooks/useUserProjects';
import { useUsers } from '../../hooks/useUsers';
import { assignProject, unassignProject } from '../../services/userProjectService';
import type { ChangeResult } from '../../user-projects.types';

/**
 * BPA-76: Users and projects, for the PPC Team (the route and the Sidebar link are ppc-team only; the backend answers 403 to every other group, ADR 0004).
 * Pick a user, see the user's projects and the groups the user is in, assign a project from the list of projects or unassign one. A refusal shows the backend's
 * text ("Project PRJ-999 does not exist", "PRJ-139 is already assigned to project-engineer"). Removing a user's last project is allowed; the screen says that
 * a restart then seeds the projects listed for that user in personas.yaml again, if it has any (ADR 0005), as a note and not a rule. Nothing is scoped by these rows yet (BPA-77
 * to BPA-79).
 */
export function UserProjects({ api }: { api: Api }) {
  const queryClient = useQueryClient();
  const users = useUsers(api);
  const projects = useProjects(api);
  const [username, setUsername] = useState('');
  const [projectRef, setProjectRef] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const busy = useRef(false); // a ref, not only state: a second click can arrive before the first re-render
  const [working, setWorking] = useState(false);
  const mine = useUserProjects(api, username);
  const user = (users.data ?? []).find((u) => u.username === username);

  function pick(value: string) {
    setUsername(value);
    setProjectRef('');
    setProblem(null);
  }

  /** A refusal can mean the screen is out of date, so the list is read again either way. */
  async function finish(result: ChangeResult, fallback: string) {
    if (result.ok) {
      setProblem(null);
      setProjectRef('');
    } else {
      setProblem(result.detail ?? `${fallback} (${result.status})`);
    }
    await queryClient.invalidateQueries({ queryKey: ['user-projects', username] });
  }

  /** One change at a time: a second click while a call is out is ignored, so a double click cannot turn a success into a "duplicate" refusal. */
  async function run(call: () => Promise<ChangeResult>, fallback: string) {
    if (busy.current) {
      return;
    }
    busy.current = true;
    setWorking(true);
    setProblem(null);
    try {
      await finish(await call(), fallback);
    } finally {
      busy.current = false;
      setWorking(false);
    }
  }

  const assign = () => run(() => assignProject(api, username, projectRef), 'The project could not be assigned');
  const unassign = (ref: string) => run(() => unassignProject(api, username, ref), 'The project could not be unassigned');

  return (
    <section aria-label="Users and projects" className="user-projects">
      {users.data === undefined ? <Spinner /> : null}
      {users.data === null ? <ErrorMessage text="The users could not be loaded" /> : null}
      {users.data ? (
        <form className="filters" onSubmit={(e) => e.preventDefault()}>
          <Field label="User">
            <select value={username} onChange={(e) => pick(e.target.value)}>
              <option value="">Select a user</option>
              {users.data.map((u) => (
                <option key={u.username} value={u.username}>
                  {u.username}
                </option>
              ))}
            </select>
          </Field>
        </form>
      ) : null}
      {user ? <p>Groups: {user.groups.length > 0 ? user.groups.join(', ') : 'none'}</p> : null}
      {username ? (
        <>
          <ErrorMessage text={problem} />
          {mine.data === undefined ? <Spinner /> : null}
          {mine.data === null ? <ErrorMessage text="The projects of the user could not be loaded" /> : null}
          {mine.data ? (
            <>
              <table className="sign-table" aria-label={`Projects of ${username}`}>
                <thead>
                  <tr>
                    <th>Project</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {mine.data.map((p) => (
                    <tr key={p.projectRef}>
                      <td>{p.projectRef}</td>
                      <td>
                        <Button aria-label={`Unassign ${p.projectRef}`} disabled={working} onClick={() => unassign(p.projectRef)}>
                          Unassign
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {mine.data.length === 0 ? <p>No projects assigned</p> : null}
              {mine.data.length === 1 ? (
                <p role="note" className="notice">
                  This is the last project of {username}. If you remove it, a restart of the application seeds the projects listed
                  for this user in personas.yaml again, if it has any (ADR 0005).
                </p>
              ) : null}
              {projects.data === undefined ? <Spinner /> : null}
              {projects.data === null ? <ErrorMessage text="The projects could not be loaded" /> : null}
              {projects.data ? (
                <form className="filters" onSubmit={(e) => e.preventDefault()}>
                  <Field label="Project">
                    <select value={projectRef} onChange={(e) => setProjectRef(e.target.value)}>
                      <option value="">Select a project</option>
                      {projects.data.map((p) => (
                        <option key={p.projectRef} value={p.projectRef}>
                          {p.projectRef}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Button className="button--primary" disabled={projectRef === '' || working} onClick={assign}>
                    Assign project
                  </Button>
                </form>
              ) : null}
            </>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
