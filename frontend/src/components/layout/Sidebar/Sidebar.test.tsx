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

// BPA-76: "Users and projects" is a PPC Team link; any other group (or none) does not see it. The backend's 403 is the real check; this only hides the link.
describe('Sidebar by group', () => {
  afterEach(cleanup);

  it('offers Users and projects to the PPC Team', () => {
    render(<Sidebar groups={['ppc-team']} />);

    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Users and projects' })).toHaveAttribute('href', '/users');
  });

  it('does not offer Users and projects to another group', () => {
    render(<Sidebar groups={['supervisor', 'timekeeper']} />);

    expect(screen.queryByRole('link', { name: 'Users and projects' })).not.toBeInTheDocument();
  });
});
