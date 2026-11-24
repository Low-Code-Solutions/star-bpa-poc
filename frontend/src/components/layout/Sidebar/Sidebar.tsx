import { NavLink } from 'react-router';

const PPC_TEAM = 'ppc-team';

/**
 * The portal's navigation (BPA-72): Home, and under it the Work Packs list, for every logged-in group. BPA-76 adds "Users and projects" for the PPC Team only.
 * The groups come in as a prop from MainLayout (the portal's group gating is prop-based, and a bare <Sidebar /> has no AuthProvider); this only hides the link,
 * the backend's 403 is the real check (ADR 0004).
 */
export function Sidebar({ groups = [] }: { groups?: string[] }) {
  return (
    <nav aria-label="Main" className="sidebar">
      <ul>
        <li>
          <NavLink to="/" end>
            Home
          </NavLink>
          <ul>
            <li>
              <NavLink to="/workpacks">Work Packs</NavLink>
            </li>
            {groups.includes(PPC_TEAM) ? (
              <li>
                <NavLink to="/users">Users and projects</NavLink>
              </li>
            ) : null}
          </ul>
        </li>
      </ul>
    </nav>
  );
}
