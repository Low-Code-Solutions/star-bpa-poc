import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import { AuthProvider } from '@/context/AuthContext';
import { AppRoutes } from '@/routes/AppRoutes';
import { apiClient } from '@/services/apiClient';

/**
 * Query defaults that keep the calls as they were before TanStack Query (BPA-47): no automatic retries, no refetch on focus.
 * A completion refetches the task list (useCompleteTask).
 */
export function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
}

// Portal (ADR 0004, ADR 0017): login, /me and logout (BPA-43, L4); the task list and the Create Work Pack screen (BPA-6).
export function App() {
  const [api] = useState(apiClient);
  const [queryClient] = useState(createQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider api={api}>
        <BrowserRouter>
          <AppRoutes api={api} />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}
