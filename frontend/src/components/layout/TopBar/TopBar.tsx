import { Icon } from '@/components/common/Icon';
import { useAuth } from '@/context/AuthContext';

/**
 * The top bar of the ui-design templates (docs/ui-screen-map.md 11.2): the menu button (phone only), then "Dark mode" and "Logout". The search and "Notifications"
 * are not here: no story builds them (Q22 closed 27 Nov 2026; BPA-132 is parked). "Logout" stays visible on a phone, where the template hides it (BPA-125 scenario 5).
 */
export function TopBar({ dark, onToggleDark, onOpenMenu }: { dark: boolean; onToggleDark: () => void; onOpenMenu: () => void }) {
  const { logout } = useAuth();
  return (
    <header className="topbar">
      <button type="button" className="topbar__menu" onClick={onOpenMenu} aria-label="Open menu">
        <Icon name="menu" />
      </button>
      <div className="topbar__actions">
        <button
          type="button"
          className="topbar__icon topbar__icon--plain"
          onClick={onToggleDark}
          aria-pressed={dark}
          aria-label="Dark mode"
          title="Dark mode"
        >
          <Icon name="moon" />
        </button>
        <button type="button" className="topbar__icon" onClick={logout} aria-label="Logout" title="Logout">
          <Icon name="logout" />
        </button>
      </div>
    </header>
  );
}
