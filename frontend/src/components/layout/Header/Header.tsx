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
      <h1 className="portal__title">STAR BPA portal</h1>
      <p className="portal__user">
        Logged in as <strong>{me.username}</strong>
      </p>
      <ul aria-label="Groups" className="portal__groups">
        {me.groups.map((g) => (
          <li key={g}>{g}</li>
        ))}
      </ul>
      <Button onClick={logout} className="portal__logout">
        Log out
      </Button>
    </>
  );
}
