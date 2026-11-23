import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen, within } from '@tests/test-utils';
import { Sidebar } from './Sidebar';

// BPA-72: the first navigation of the portal. Home, and under it the Work Packs list, for every logged-in group (the page behind it is read only).
describe('Sidebar', () => {
  afterEach(cleanup);

  it('offers Home and, under it, the Work Packs list', () => {
    render(<Sidebar />);

    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
    expect(within(nav).getByRole('link', { name: 'Work Packs' })).toHaveAttribute('href', '/workpacks');
  });
});
