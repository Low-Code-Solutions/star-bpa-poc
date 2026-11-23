import { Route, Routes } from 'react-router';
import { MainLayout } from '@/layouts/MainLayout';
import { TasksPage } from '@/pages/TasksPage';
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
          <Route index element={<TasksPage api={api} />} />
          <Route path="workpacks" element={<WorkPacksPage api={api} />} />
          <Route path="workpacks/:workpackNumber" element={<WorkPackDetailPage api={api} />} />
          <Route path="*" element={<TasksPage api={api} />} />
        </Route>
      </Route>
    </Routes>
  );
}
