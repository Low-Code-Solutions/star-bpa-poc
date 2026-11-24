import { UserProjects } from '@/features/user-projects';
import type { Api } from '@/services/apiClient';

/** BPA-76: Users and projects, a thin page for the PPC Team (routed under ProtectedRoute groups=['ppc-team'], ADR 0017). */
export function UsersPage({ api }: { api: Api }) {
  return (
    <>
      <h2>Users and projects</h2>
      <UserProjects api={api} />
    </>
  );
}
