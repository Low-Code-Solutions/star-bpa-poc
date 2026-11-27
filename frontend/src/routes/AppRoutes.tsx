import { Route, Routes } from 'react-router';
import { MainLayout } from '@/layouts/MainLayout';
import { ProjectsPage } from '@/pages/ProjectsPage';
import { DashboardPage } from '@/pages/DashboardPage';
import { UsersPage } from '@/pages/UsersPage';
import { WorkPackDetailPage } from '@/pages/WorkPackDetailPage';
import { WorkPacksPage } from '@/pages/WorkPacksPage';
import type { Api } from '@/services/apiClient';
import { ProtectedRoute } from './ProtectedRoute';

/**
 * App-level routes (ADR 0017). Every URL renders the portal and keeps its URL, as before routing (no redirect). Task
 * screens open in place from the task list; per-task URLs come with the visual redesign. A route for a single group is
 * wrapped in <ProtectedRoute groups={[...]}>.
 */
export function AppRoutes({ api }: { api: Api }) {
  return (
    <Routes>
      <Route element={<ProtectedRoute />}>
        <Route element={<MainLayout />}>
          <Route index element={<DashboardPage api={api} />} />
          <Route path="workpacks" element={<WorkPacksPage api={api} />} />
          <Route path="workpacks/:workpackNumber" element={<WorkPackDetailPage api={api} />} />
          <Route path="projects" element={<ProjectsPage api={api} />} />
          <Route element={<ProtectedRoute groups={['ppc-team']} />}>
            <Route path="users" element={<UsersPage api={api} />} />
          </Route>
          <Route path="*" element={<DashboardPage api={api} />} />
        </Route>
      </Route>
    </Routes>
  );
}
