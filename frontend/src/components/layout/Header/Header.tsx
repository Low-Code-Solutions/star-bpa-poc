import { Button } from '@/components/common/Button';
import { useAuth } from '@/context/AuthContext';

/** The portal heading, the session user and their Camunda groups (S5, S6), and Log out. Same elements as before BPA-47. */
export function Header() {
  const { me, logout } = useAuth();
  if (!me) {
    return null;
  }
  return (
    <>
      <h1>STAR BPA portal</h1>
      <p>
        Logged in as <strong>{me.username}</strong>
      </p>
      <ul aria-label="Groups">
        {me.groups.map((g) => (
          <li key={g}>{g}</li>
        ))}
      </ul>
      <Button onClick={logout}>Log out</Button>
    </>
  );
}
