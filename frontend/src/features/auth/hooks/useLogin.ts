import { useState } from 'react';
import { useAuth } from '@/context/AuthContext';

/** The login form's call and its error texts (BPA-43: 401 and 503 have their own; anything else shows the status; BPA-125: the 401 text is "Username or password is incorrect"). */
export function useLogin() {
  const { login } = useAuth();
  const [error, setError] = useState<{ text: string; status: number } | null>(null);

  /** onAnswered runs once the backend has answered, before any error is shown (the form clears the password there). */
  async function submit(username: string, password: string, onAnswered: () => void) {
    setError(null);
    const result = await login(username, password);
    onAnswered();
    if ('status' in result) {
      setError({
        status: result.status,
        text:
          result.status === 401
            ? 'Username or password is incorrect'
            : result.status === 503
              ? 'Login is not available right now; try again later'
              : `Login failed (${result.status})`,
      });
    }
  }

  /** The templates clear the message as soon as the user types again. */
  function clearError() {
    setError(null);
  }

  return { submit, error, clearError };
}
