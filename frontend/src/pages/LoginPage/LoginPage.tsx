import { LoginForm } from '@/features/auth';

/** Shown in place, at any URL, while nobody is logged in (ProtectedRoute). */
export function LoginPage() {
  return (
    <main className="portal portal--login">
      <h1 className="portal__title">STAR BPA portal</h1>
      <LoginForm />
    </main>
  );
}
