import { Outlet } from 'react-router';
import { Header } from '@/components/layout/Header';
import { Sidebar } from '@/components/layout/Sidebar';

/** The logged-in portal: <main> with the header, the (empty) sidebar and the page. */
export function MainLayout() {
  return (
    <main className="portal">
      <Header />
      <Sidebar />
      <Outlet />
    </main>
  );
}
