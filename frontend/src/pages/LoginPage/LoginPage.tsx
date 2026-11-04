import { LoginForm } from '@/features/auth';

/** Shown in place, at any URL, while nobody is logged in (ProtectedRoute). */
export function LoginPage() {
  return (
    <main>
      <h1>STAR BPA portal</h1>
      <LoginForm />
    </main>
  );
}
