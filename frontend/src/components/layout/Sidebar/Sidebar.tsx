import { NavLink } from 'react-router';

const PPC_TEAM = 'ppc-team';

/**
 * The portal's navigation (BPA-72): Home, and under it the Work Packs list, for every logged-in group. BPA-77 adds "Projects and master data", also for every group
 * (the backend limits it to the user's own projects). BPA-76 adds "Users and projects" for the PPC Team only.
 * The groups come in as a prop from MainLayout (the portal's group gating is prop-based, and a bare <Sidebar /> has no AuthProvider); this only hides the link,
 * the backend's 403 is the real check (ADR 0004). BPA-125: onNavigate lets the phone drawer close when a link is tapped (the templates' drawer buttons navigate and close).
 */
export function Sidebar({ groups = [], onNavigate }: { groups?: string[]; onNavigate?: () => void }) {
  return (
    <nav aria-label="Main" className="sidebar">
      <ul>
        <li>
          <NavLink to="/" end onClick={onNavigate}>
            Home
          </NavLink>
          <ul>
            <li>
              <NavLink to="/workpacks" onClick={onNavigate}>
                Work Packs
              </NavLink>
            </li>
            <li>
              <NavLink to="/projects" onClick={onNavigate}>
                Projects and master data
              </NavLink>
            </li>
            {groups.includes(PPC_TEAM) ? (
              <li>
                <NavLink to="/users" onClick={onNavigate}>
                  Users and projects
                </NavLink>
              </li>
            ) : null}
          </ul>
        </li>
      </ul>
    </nav>
  );
}
