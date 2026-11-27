import { useState, type FormEvent } from 'react';
import { Icon } from '@/components/common/Icon';
import { useLogin } from '../../hooks/useLogin';

const USERNAME_REQUIRED = 'Enter your username';
const PASSWORD_REQUIRED = 'Enter your password';

/**
 * Username and password, posted once to the backend (S5); the password is cleared after the call. BPA-125 (U-D90, ADR 0005): the checks run here before anything is
 * posted ("Enter your username", "Enter your password", the field marked aria-invalid), the backend's 401 shows "Username or password is incorrect", and there is no
 * list of test accounts. The error line is the template's: a red box with an icon, role="alert".
 */
export function LoginForm() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [problem, setProblem] = useState<{ field: 'username' | 'password' | null; text: string } | null>(null);
  const { submit, error } = useLogin();

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

  const shown = problem?.text ?? error;
  return (
    <form className="login__form" onSubmit={onSubmit} noValidate>
      <label className="login__field">
        Username
        <input
          className="login__input"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
          aria-invalid={problem?.field === 'username'}
        />
      </label>
      <label className="login__field">
        Password
        <input
          className="login__input"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          aria-invalid={problem?.field === 'password'}
        />
      </label>
      {shown ? (
        <div role="alert" className="login__error">
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
