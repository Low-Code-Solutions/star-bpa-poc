import { useEffect, useState, type FormEvent } from 'react';
import { apiClient } from './api/client';
import { TaskList } from './TaskList';
import type { components } from './api/schema';

type Me = components['schemas']['Me'];

// Portal (ADR 0004): log in, see /me, log out (BPA-43, L4); the task list and the Create Work Pack screen (BPA-6).
export function App() {
  const [api] = useState(apiClient);
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Also gives the browser its XSRF-TOKEN cookie before the first POST (S26).
    api.GET('/api/v1/me').then(({ data }) => {
      setMe(data ?? null);
      setLoading(false);
    });
  }, [api]);

  if (loading) {
    return <p>Loading…</p>;
  }
  if (!me) {
    return <LoginForm onLogin={setMe} api={api} />;
  }
  return (
    <main>
      <h1>STAR BPA portal</h1>
      <p>
        Logged in as <strong>{me.username}</strong>
      </p>
      <ul aria-label="Groups">
        {me.groups.map((g) => (
          <li key={g}>{g}</li>
        ))}
      </ul>
      <button
        type="button"
        onClick={async () => {
          await api.POST('/api/v1/auth/logout');
          setMe(null);
        }}
      >
        Log out
      </button>
      <TaskList api={api} groups={me.groups} />
    </main>
  );
}

function LoginForm({ api, onLogin }: { api: ReturnType<typeof apiClient>; onLogin: (me: Me) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const { data, response } = await api.POST('/api/v1/auth/login', { body: { username, password } });
    setPassword('');
    if (data) {
      onLogin(data);
    } else {
      setError(
        response.status === 401
          ? 'Wrong username or password'
          : response.status === 503
            ? 'Login is not available right now; try again later'
            : `Login failed (${response.status})`,
      );
    }
  }

  return (
    <main>
      <h1>STAR BPA portal</h1>
      <form onSubmit={submit}>
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
        <button type="submit">Log in</button>
      </form>
      {error && <p role="alert">{error}</p>}
    </main>
  );
}
