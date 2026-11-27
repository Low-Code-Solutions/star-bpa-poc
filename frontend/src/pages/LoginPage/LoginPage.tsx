import { LoginForm } from '@/features/auth';

/**
 * Sign in as 01-Sign-in.html draws it (docs/ui-screen-map.md 11.1): a centred card on a pale page. Shown in place, at any URL, while nobody is logged in
 * (ProtectedRoute). It is never dark: the "dark" class belongs to the shell of the logged-in portal.
 */
export function LoginPage() {
  return (
    <div className="login">
      <main className="login__card">
        <div className="login__brand">
          <span className="login__logo hfont">STAR</span>
          <h1>Sign in</h1>
          <p>STAR Portal</p>
        </div>
        <LoginForm />
      </main>
    </div>
  );
}
