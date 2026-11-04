import { Navigate, Route, Routes } from 'react-router';
import { MainLayout } from '@/layouts/MainLayout';
import { TasksPage } from '@/pages/TasksPage';
import type { Api } from '@/services/apiClient';
import { ProtectedRoute } from './ProtectedRoute';

/**
 * App-level routes (ADR 0017). Task screens open in place from the task list; per-task URLs come with the visual redesign.
 * A route for a single group is wrapped in <ProtectedRoute groups={[...]}>.
 */
export function AppRoutes({ api }: { api: Api }) {
  return (
    <Routes>
      <Route element={<ProtectedRoute />}>
        <Route element={<MainLayout />}>
          <Route index element={<TasksPage api={api} />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
