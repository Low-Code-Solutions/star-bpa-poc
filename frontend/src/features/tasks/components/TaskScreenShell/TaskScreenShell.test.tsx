import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@tests/test-utils';
import userEvent from '@testing-library/user-event';
import { apiClient } from '@/services/apiClient';
import { TaskScreenShell } from './TaskScreenShell';

// BPA-50: canComplete=false is for a screen that saves line by line and does not complete its task (Book hours; completing it means
// "Send to Timekeeper", a later story). No Complete button, and Enter in a field must not complete the task either.
const TASK = '2251799813692600';

function fakeBackend() {
  const paths: string[] = [];
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    paths.push(`${input.method} ${path}`);
    if (path === `/api/v1/tasks/${TASK}`) {
      return new Response(
        JSON.stringify({
          key: TASK,
          name: 'X',
          elementId: 'UserTask_BookHours',
          processInstanceKey: '1',
          creationDate: '2026-11-17T06:00:00Z',
          variables: {},
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    }
    return new Response(null, { status: 204 });
  });
  return { fetchMock, paths };
}

describe('TaskScreenShell canComplete', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('without a Complete button, Enter in a field completes nothing', async () => {
    const { fetchMock, paths } = fakeBackend();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(
      <TaskScreenShell
        api={apiClient()}
        taskKey={TASK}
        title="Book daily hours"
        onDone={vi.fn()}
        check={() => []}
        variables={() => ({})}
        canComplete={false}
      >
        <input aria-label="Hours" />
      </TaskScreenShell>,
    );
    await screen.findByLabelText('Hours');

    await user.type(screen.getByLabelText('Hours'), '8{Enter}');

    expect(screen.queryByRole('button', { name: 'Complete task' })).not.toBeInTheDocument();
    expect(paths.some((p) => p.endsWith('/complete'))).toBe(false);
  });

  it('by default the Complete button is there and completes the task', async () => {
    const { fetchMock, paths } = fakeBackend();
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    const onDone = vi.fn();
    render(
      <TaskScreenShell
        api={apiClient()}
        taskKey={TASK}
        title="Book daily hours"
        onDone={onDone}
        check={() => []}
        variables={() => ({})}
      >
        <input aria-label="Hours" />
      </TaskScreenShell>,
    );
    await screen.findByLabelText('Hours');

    await user.click(screen.getByRole('button', { name: 'Complete task' }));

    await vi.waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(paths).toContain(`POST /api/v1/tasks/${TASK}/complete`);
  });
});
