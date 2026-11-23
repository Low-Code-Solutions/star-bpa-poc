import { NavLink } from 'react-router';

/**
 * Navigation (BPA-72, the first): Home, and under it the Work Packs list, for every logged-in group (the screens only hide what a group cannot use; the
 * list is read only and open to all). The visual redesign (a later task) restyles it; the links stay.
 */
export function Sidebar() {
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
          </ul>
        </li>
      </ul>
    </nav>
  );
}
