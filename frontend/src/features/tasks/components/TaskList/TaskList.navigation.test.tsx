import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { createQueryClient } from '@/App';
import { apiClient } from '@/services/apiClient';
import { TaskList } from './TaskList';

// BPA-73: the Work Pack detail's "Open tasks" panel sends the user to Home with the task to open (navigation state). The list opens that task when it is
// one of the user's own tasks, and does nothing for a key that is not in the user's list (the backend would answer 403 or 404 for it anyway).
const TASK = {
  key: '2251799813700001',
  name: 'Book hours',
  elementId: 'UserTask_BookHours',
  processInstanceKey: '2251799813700000',
  creationDate: '2026-11-19T07:00:00Z',
  workpackNumber: 'WP-000457',
};

function open(state: unknown) {
  const requested: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request) => {
      const path = new URL(input.url).pathname;
      requested.push(path);
      const body =
        path === '/api/v1/tasks'
          ? [TASK]
          : path === `/api/v1/tasks/${TASK.key}`
            ? { ...TASK, variables: {}, validationMessages: [] }
            : [];
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[{ pathname: '/', state }]}>
        <TaskList api={apiClient()} groups={['supervisor']} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return requested;
}

describe('TaskList opened from the Work Pack detail', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('opensTheTaskItWasSentWith', async () => {
    const requested = open({ taskKey: TASK.key });

    await waitFor(() => expect(requested).toContain(`/api/v1/tasks/${TASK.key}`)); // the task screen read the task: it is open
  });

  it('opensNothingForATaskThatIsNotInTheList', async () => {
    const requested = open({ taskKey: '2251799813799999' });

    await waitFor(() => expect(requested).toContain('/api/v1/tasks'));
    expect(requested.filter((p) => p.startsWith('/api/v1/tasks/'))).toHaveLength(0);
  });
});
