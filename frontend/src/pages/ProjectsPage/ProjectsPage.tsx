import { ProjectMasterData } from '@/features/projects';
import type { Api } from '@/services/apiClient';

/** BPA-77: Projects and master data, a thin page for every persona (the backend limits it to the user's own projects, ADR 0017). */
export function ProjectsPage({ api }: { api: Api }) {
  return (
    <>
      <h2>Projects and master data</h2>
      <ProjectMasterData api={api} />
    </>
  );
}
