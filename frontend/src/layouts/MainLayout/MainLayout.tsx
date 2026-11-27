import { useEffect, useState } from 'react';
import { Outlet } from 'react-router';
import { Sidebar } from '@/components/layout/Sidebar';
import { TopBar } from '@/components/layout/TopBar';
import { useAuth } from '@/context/AuthContext';
import { personaOf } from '@/utils/personas';

/**
 * The logged-in portal as the docs/ui-prototype templates draw it (BPA-125; docs/ui-screen-map.md 11.2 and 11.4): a sidebar column (the logo, the navigation, the persona),
 * the top bar and the page. Dark mode is the templates' one rule: the class "dark" on the shell root (a CSS filter). It is white by default and is not remembered:
 * the state lives here, so a reload, or a logout and a new login, starts white (decision of Ahsan, 27 Nov 2026).
 */
export function MainLayout() {
  const { me } = useAuth();
  const [dark, setDark] = useState(false);
  const [drawer, setDrawer] = useState(false);
  const persona = me ? personaOf(me) : null;
  const groups = me?.groups ?? [];

  // Escape closes the phone drawer (QA review of BPA-125).
  useEffect(() => {
    if (!drawer) {
      return;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setDrawer(false);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [drawer]);

  return (
    <div className={dark ? 'shell dark' : 'shell'}>
      <div className="shell__side">
        <div className="shell__logo hfont">STAR</div>
        <Sidebar groups={groups} />
        {persona ? (
          <div className="shell__persona">
            <span className="shell__avatar" aria-hidden="true">
              {persona.initials}
            </span>
            <span>{persona.name}</span>
          </div>
        ) : null}
      </div>
      <div className="shell__col">
        <TopBar dark={dark} onToggleDark={() => setDark((d) => !d)} onOpenMenu={() => setDrawer(true)} />
        <main className="shell__main">
          <Outlet />
        </main>
      </div>
      {drawer ? (
        <div className="drawer">
          <div className="drawer__panel">
            <div className="drawer__head">
              <span className="hfont drawer__logo">STAR</span>
              <button type="button" className="drawer__close" onClick={() => setDrawer(false)} aria-label="Close menu">
                ×
              </button>
            </div>
            <Sidebar groups={groups} onNavigate={() => setDrawer(false)} />
          </div>
          <button type="button" className="drawer__shade" onClick={() => setDrawer(false)} aria-label="Close menu" />
        </div>
      ) : null}
    </div>
  );
}
