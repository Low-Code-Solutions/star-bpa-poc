import { useState } from 'react';
import { useAuth } from '@/context/AuthContext';

/** The login form's call and its error texts (BPA-43: 401 and 503 have their own; anything else shows the status). */
export function useLogin() {
  const { login } = useAuth();
  const [error, setError] = useState<string | null>(null);

  /** onAnswered runs once the backend has answered, before any error is shown (the form clears the password there). */
  async function submit(username: string, password: string, onAnswered: () => void) {
    setError(null);
    const result = await login(username, password);
    onAnswered();
    if ('status' in result) {
      setError(
        result.status === 401
          ? 'Username or password is incorrect'
          : result.status === 503
            ? 'Login is not available right now; try again later'
            : `Login failed (${result.status})`,
      );
    }
  }

  return { submit, error };
}
