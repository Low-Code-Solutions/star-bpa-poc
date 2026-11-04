import type { ReactElement } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { render as rtlRender, type RenderOptions } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { createQueryClient } from '@/App';

// Testing Library with the providers a feature component needs (BPA-47, ADR 0017): a fresh QueryClient per render (same
// defaults as the app) and a MemoryRouter. Tests that render <App /> use Testing Library directly: App has its own.
export * from '@testing-library/react';

export function render(ui: ReactElement, options?: Omit<RenderOptions, 'wrapper'>) {
  const queryClient = createQueryClient();
  return rtlRender(ui, {
    wrapper: ({ children }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    ),
    ...options,
  });
}
