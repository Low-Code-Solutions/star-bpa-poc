import { useState, type FormEvent } from 'react';
import { Button } from '@/components/common/Button';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { useLogin } from '../../hooks/useLogin';

/** Username and password, posted once to the backend (S5); the password is cleared after the call. */
export function LoginForm() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const { submit, error } = useLogin();

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    await submit(username, password, () => setPassword(''));
  }

  return (
    <>
      <form onSubmit={onSubmit}>
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <Button type="submit">Log in</Button>
      </form>
      <ErrorMessage text={error} />
    </>
  );
}
