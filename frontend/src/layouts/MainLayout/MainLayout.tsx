import { Outlet } from 'react-router';
import { Header } from '@/components/layout/Header';
import { Sidebar } from '@/components/layout/Sidebar';
import { useAuth } from '@/context/AuthContext';

/** The logged-in portal: <main> with the header, the sidebar (links by group) and the page. */
export function MainLayout() {
  const { me } = useAuth();
  return (
    <main className="portal">
      <Header />
      <Sidebar groups={me?.groups ?? []} />
      <Outlet />
    </main>
  );
}
