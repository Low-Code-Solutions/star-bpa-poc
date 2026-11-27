import { PageHeader } from '@/components/common/PageHeader';
import { useAuth } from '@/context/AuthContext';
import { useMyProjects } from '@/features/projects';
import { TasksPage } from '@/pages/TasksPage';
import type { Api } from '@/services/apiClient';
import { personaOf } from '@/utils/personas';

/**
 * The Dashboard landing (BPA-125; docs/ui-screen-map.md 11.3): the page header "Dashboard" with "<Role> · <Department> · <n> assigned projects" as the template writes it.
 * The dashboard sections are BPA-127's; until then the task list and the panels that were the home page stay under the header (Q14).
 */
export function DashboardPage({ api }: { api: Api }) {
  const { me } = useAuth();
  const projects = useMyProjects(api);
  if (!me) {
    return null;
  }
  const persona = personaOf(me);
  const count = projects.data ? projects.data.length : null;
  const subtitle = [
    persona.name,
    persona.department,
    count === null ? null : `${count} assigned ${count === 1 ? 'project' : 'projects'}`,
  ]
    .filter(Boolean)
    .join(' · ');
  return (
    <>
      <PageHeader title="Dashboard" subtitle={subtitle} icon="dash" />
      <TasksPage api={api} />
    </>
  );
}
