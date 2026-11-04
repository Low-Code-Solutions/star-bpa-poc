import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './schema';

// The one API client (ADR 0017): openapi-fetch typed by schema.d.ts, which `npm run gen:api` generates from
// ../api/openapi.yaml (S21). Never edit schema.d.ts by hand.
//
// S26: every state-changing call sends the XSRF-TOKEN cookie back as the X-XSRF-TOKEN header. The session cookie is
// HttpOnly, so React never sees it and never holds the password after the login call (S5).
const csrf: Middleware = {
  onRequest({ request }) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      const token = document.cookie
        .split('; ')
        .find((c) => c.startsWith('XSRF-TOKEN='))
        ?.slice('XSRF-TOKEN='.length);
      if (token) {
        request.headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
      }
    }
    return request;
  },
};

export function apiClient() {
  // fetch is looked up per call so tests can stub it.
  const client = createClient<paths>({
    baseUrl: window.location.origin,
    credentials: 'same-origin',
    fetch: (request) => globalThis.fetch(request),
  });
  client.use(csrf);
  return client;
}

/** The client type every service and feature takes as `api`. */
export type Api = ReturnType<typeof apiClient>;
