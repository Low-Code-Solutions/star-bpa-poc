import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '@/App';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// BPA-125: sign in and sign out, as the prototype's 01-Sign-in.html shows them, with the texts of the Jira Gherkin. The Jira user names ("tk.user") are the
// prototype's; the portal's personas are the eleven Camunda groups (docs/04), so "tk.user" is `timekeeper` here (plan Q3). The tests named BPA124_ cover dark mode,
// which the templates have and no story covers (docs/ui-screen-map.md section 12; decision of Ahsan, 27 Nov 2026). The backend is a fetch stub.
// The stylesheet text, read as a file: Vitest turns a .css import into an empty module.
const styles = readFileSync(resolve(process.cwd(), 'src/styles/global.css'), 'utf8');
const PEOPLE: Record<string, string> = { timekeeper: 'right', 'time-method-analyst': 'right' };

function fakeBackend() {
  let session: { username: string; groups: string[] } | null = null;
  const json = (status: number, body: unknown, type = 'application/json') =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });
  const fetchMock = vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname;
    if (path === '/api/v1/me') {
      return session ? json(200, session) : json(401, { status: 401 });
    }
    if (path === '/api/v1/auth/login') {
      const { username, password } = JSON.parse(await input.clone().text()) as { username: string; password: string };
      if (PEOPLE[username] !== password) {
        return json(
          401,
          { status: 401, title: 'Unauthorized', detail: 'Wrong username or password' },
          'application/problem+json',
        );
      }
      session = { username, groups: [username] };
      return json(200, session);
    }
    if (path === '/api/v1/auth/logout') {
      session = null;
      return new Response(null, { status: 204 });
    }
    if (path === '/api/v1/me/projects') {
      return session ? json(200, [{ projectRef: 'PRJ-139' }]) : json(401, { status: 401 });
    }
    if (path === '/api/v1/tasks') {
      return session ? json(200, []) : json(401, { status: 401 });
    }
    return json(404, {});
  });
  return {
    fetchMock,
    posts: () => fetchMock.mock.calls.map(([r]) => r as Request).filter((r) => new URL(r.url).pathname === '/api/v1/auth/login'),
  };
}

async function openSignIn() {
  const backend = fakeBackend();
  vi.stubGlobal('fetch', backend.fetchMock);
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('heading', { name: 'Sign in' });
  return { ...backend, user };
}

async function signInAs(user: ReturnType<typeof userEvent.setup>, username: string, password = 'right') {
  await user.type(screen.getByLabelText('Username'), username);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('Sign in and sign out (BPA-125)', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.replaceState(null, '', '/');
  });

  it('BPA125_validSignInOpensTheRoleDashboard', async () => {
    const { user } = await openSignIn();

    await signInAs(user, 'timekeeper');

    const heading = await screen.findByRole('heading', { level: 1, name: 'Dashboard' });
    expect(heading).toBeInTheDocument();
    expect(screen.getByText(/Timekeeper · Production/)).toBeInTheDocument(); // the sub-title names the persona and its department
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('BPA125_emptyUsername', async () => {
    const { user, posts } = await openSignIn();

    await user.type(screen.getByLabelText('Password'), 'right');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter your username');
    expect(screen.getByLabelText('Username')).toHaveAttribute('aria-invalid', 'true');
    expect(posts()).toHaveLength(0);
  });

  it('BPA125_emptyPassword', async () => {
    const { user, posts } = await openSignIn();

    await user.type(screen.getByLabelText('Username'), 'timekeeper');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter your password');
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true');
    expect(posts()).toHaveLength(0);
  });

  it('BPA125_unknownUserOrWrongPassword', async () => {
    const { user } = await openSignIn();

    await signInAs(user, 'xx.user', 'whatever');

    expect(await screen.findByRole('alert')).toHaveTextContent('Username or password is incorrect');
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Dashboard' })).not.toBeInTheDocument();
  });

  // The template marks the username field for "Username or password is incorrect" (lgEk 'u'), ties no text to it, and clears the message when the user types.
  it('a wrong login marks the username field, links the message to it, and typing clears both', async () => {
    const { user } = await openSignIn();
    await signInAs(user, 'xx.user', 'whatever');
    const alert = await screen.findByRole('alert');

    expect(screen.getByLabelText('Username')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Username')).toHaveAttribute('aria-describedby', alert.id);

    await user.type(screen.getByLabelText('Username'), 'x');

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Username')).not.toHaveAttribute('aria-invalid', 'true');
  });

  it('BPA125_logout', async () => {
    const { user } = await openSignIn();
    await signInAs(user, 'timekeeper');
    await screen.findByRole('heading', { name: 'Dashboard' });

    await user.click(screen.getByRole('button', { name: 'Logout' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('the Sign in screen has no test-account list and no search, as the build requires (ADR 0005, U-D90)', async () => {
    await openSignIn();

    expect(screen.queryByLabelText('Test accounts')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });
});

describe('The shell and dark mode (templates; decision of Ahsan, 27 Nov 2026: no story)', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123';
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.replaceState(null, '', '/');
  });

  async function signedIn() {
    const ctx = await openSignIn();
    await signInAs(ctx.user, 'timekeeper');
    await screen.findByRole('heading', { name: 'Dashboard' });
    return ctx;
  }

  const root = () => screen.getByRole('button', { name: 'Dark mode' }).closest('.shell') as HTMLElement;

  it('BPA124_darkModeIsOffByDefault', async () => {
    await signedIn();

    expect(root()).not.toHaveClass('dark');
    expect(screen.getByRole('button', { name: 'Dark mode' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('BPA124_darkModeSwitchesOnWithTheIcon', async () => {
    const { user } = await signedIn();

    await user.click(screen.getByRole('button', { name: 'Dark mode' }));

    expect(root()).toHaveClass('dark');
    expect(screen.getByRole('button', { name: 'Dark mode' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('BPA124_darkModeSwitchesOff', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('button', { name: 'Dark mode' }));

    await user.click(screen.getByRole('button', { name: 'Dark mode' }));

    expect(root()).not.toHaveClass('dark');
    expect(screen.getByRole('button', { name: 'Dark mode' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('BPA124_signInIsNeverDark', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('button', { name: 'Dark mode' }));

    await user.click(screen.getByRole('button', { name: 'Logout' }));

    await screen.findByRole('heading', { name: 'Sign in' });
    expect(document.querySelector('.dark')).toBeNull();
    await signInAs(userEvent.setup(), 'timekeeper'); // and a new sign-in starts light: nothing is remembered
    await waitFor(() => expect(screen.getByRole('button', { name: 'Dark mode' })).toHaveAttribute('aria-pressed', 'false'));
  });

  it('BPA124_theDarkRuleIsTheTemplatesFilter', () => {
    // docs/ui-prototype/03-Personas-all.html: .dark { filter: invert(.92) hue-rotate(180deg); background: #FFFFFF }
    expect(styles).toMatch(/\.dark\s*\{[^}]*filter:\s*invert\(0?\.92\)\s*hue-rotate\(180deg\)/);
    expect(styles).toMatch(/\.dark\s*\{[^}]*background:\s*#ffffff/i);
  });

  it('the top bar has no search and no Notifications button (no story builds them; Q22 and BPA-132)', async () => {
    await signedIn();

    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Notifications' })).not.toBeInTheDocument();
  });

  it('the menu button opens a drawer on a phone and Close menu closes it', async () => {
    const { user } = await signedIn();

    await user.click(screen.getByRole('button', { name: 'Open menu' }));
    expect(screen.getAllByRole('navigation', { name: 'Main' }).length).toBeGreaterThan(0);
    await user.click(screen.getAllByRole('button', { name: 'Close menu' })[0]);

    expect(screen.queryByRole('button', { name: 'Close menu' })).not.toBeInTheDocument();
  });

  // QA review (BPA-125 at 4e22197): the template's drawer buttons navigate and close; tapping a menu link must not leave the drawer over the new page.
  it('a menu link tapped in the drawer opens the page and closes the drawer', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('button', { name: 'Open menu' }));
    const drawer = document.querySelector('.drawer') as HTMLElement;

    await user.click(within(drawer).getByRole('link', { name: 'Work Packs' }));

    expect(window.location.pathname).toBe('/workpacks');
    expect(document.querySelector('.drawer')).toBeNull();
  });

  it('Escape closes the drawer', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('button', { name: 'Open menu' }));

    await user.keyboard('{Escape}');

    expect(document.querySelector('.drawer')).toBeNull();
  });

  // QA review: the old page colour (--plate, grey) is the "different from the sheet" colour of the existing screens (row hover, context panel, return notice);
  // the page itself is white now, so --plate must not be white or those cues vanish.
  it('BPA124_theLegacyPlateColourIsNotTheWhitePage', () => {
    expect(styles).not.toMatch(/--plate:\s*var\(--surface\)/);
    expect(styles).not.toMatch(/--plate:\s*#fff/i);
    expect(styles).not.toMatch(/#fbeceb/i); // the error notice uses the template's danger tint token
  });

  it('the Dashboard sub-title says "1 assigned project" for one project (the template\'s plural slip is not copied)', async () => {
    await signedIn();

    expect(await screen.findByText(/Timekeeper · Production · 1 assigned project$/)).toBeInTheDocument();
  });
});
