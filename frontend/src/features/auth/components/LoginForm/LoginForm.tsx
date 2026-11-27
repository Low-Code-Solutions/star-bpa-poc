import { useState, type FormEvent } from 'react';
import { Icon } from '@/components/common/Icon';
import { useLogin } from '../../hooks/useLogin';

const USERNAME_REQUIRED = 'Enter your username';
const PASSWORD_REQUIRED = 'Enter your password';
const ERROR_ID = 'login-error';

/**
 * Username and password, posted once to the backend (S5); the password is cleared after the call. BPA-125 (U-D90, ADR 0005): the checks run here before anything is
 * posted ("Enter your username", "Enter your password", the field marked aria-invalid), the backend's 401 shows "Username or password is incorrect" and marks the
 * username field (as the template does), the message is tied to that field (aria-describedby), it goes away when the user types, and there is no list of test accounts.
 * The error line is the template's: a red box with an icon, role="alert".
 */
export function LoginForm() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [problem, setProblem] = useState<{ field: 'username' | 'password'; text: string } | null>(null);
  const { submit, error, clearError } = useLogin();

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (username.trim() === '') {
      setProblem({ field: 'username', text: USERNAME_REQUIRED });
      return;
    }
    if (password === '') {
      setProblem({ field: 'password', text: PASSWORD_REQUIRED });
      return;
    }
    setProblem(null);
    await submit(username, password, () => setPassword(''));
  }

  function typed() {
    setProblem(null);
    clearError();
  }

  const shown = problem?.text ?? error?.text ?? null;
  // The field the message belongs to: a failed check names its own; the backend's 401 is shown on the username, as the template does for an unknown user.
  const invalidField = problem?.field ?? (error?.status === 401 ? 'username' : null);
  return (
    <form className="login__form" onSubmit={onSubmit} noValidate>
      <label className="login__field">
        Username
        <input
          className="login__input"
          value={username}
          onChange={(e) => {
            setUsername(e.target.value);
            typed();
          }}
          autoComplete="username"
          aria-invalid={invalidField === 'username'}
          aria-describedby={shown && invalidField === 'username' ? ERROR_ID : undefined}
        />
      </label>
      <label className="login__field">
        Password
        <input
          className="login__input"
          type="password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value);
            typed();
          }}
          autoComplete="current-password"
          aria-invalid={invalidField === 'password'}
          aria-describedby={shown && invalidField === 'password' ? ERROR_ID : undefined}
        />
      </label>
      {shown ? (
        <div role="alert" id={ERROR_ID} className="login__error">
          <Icon name="alert" size={16} />
          {shown}
        </div>
      ) : null}
      <button type="submit" className="login__button">
        Sign in
      </button>
    </form>
  );
}
