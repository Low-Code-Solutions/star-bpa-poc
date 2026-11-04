package com.lcs.star.bpa.api;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/** Test helper: a cookie-keeping HTTP client that behaves like the React portal (S26: CSRF token from the XSRF-TOKEN cookie). */
public final class TestBrowser {

    private final String base;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;

    public TestBrowser(int port) {
        this.base = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    public HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    /** A state-changing call that sends the CSRF token back, as React does. */
    public HttpResponse<String> post(String path, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        String token = cookie("XSRF-TOKEN");
        if (token != null) {
            request.header("X-XSRF-TOKEN", token);
        }
        return send(request);
    }

    public HttpResponse<String> postWithoutCsrfToken(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    /** GET /api/v1/me first so the XSRF-TOKEN cookie is set, then log in. */
    public HttpResponse<String> login(String username, String password) {
        get("/api/v1/me");
        return post("/api/v1/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    public String cookie(String name) {
        List<HttpCookie> all = cookies.getCookieStore().getCookies();
        return all.stream().filter(c -> c.getName().equals(name)).map(HttpCookie::getValue).findFirst().orElse(null);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
