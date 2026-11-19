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

    /** GET with an Accept header, for a body the portal asks for by type (the result as text/csv, BPA-12). */
    public HttpResponse<String> getWithAccept(String path, String accept) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Accept", accept).GET());
    }

    /**
     * A multipart/form-data POST with one file part named {@code file}, as the portal's upload sends it (BPA-12); the CSRF token goes back
     * as in {@link #post}. A null {@code content} sends the request with no file part at all.
     */
    public HttpResponse<String> postFile(String path, String fileName, byte[] content) {
        String boundary = "----bpa" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        try {
            if (content != null) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                        + "\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body.write(content);
                body.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nno file\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        String token = cookie("XSRF-TOKEN");
        if (token != null) {
            request.header("X-XSRF-TOKEN", token);
        }
        return send(request);
    }

    /**
     * BPA-59: a multipart/form-data POST with the text parts {@code fields} (for example the task key) and one file part named {@code file}; the CSRF
     * token goes back as in {@link #post}. Added for the completion report upload, which names its task in a second part.
     */
    public HttpResponse<String> postMultipart(String path, java.util.Map<String, String> fields, String fileName, byte[] content) {
        String boundary = "----bpa" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        try {
            for (java.util.Map.Entry<String, String> field : fields.entrySet()) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (content != null) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                        + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body.write(content);
                body.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        String token = cookie("XSRF-TOKEN");
        if (token != null) {
            request.header("X-XSRF-TOKEN", token);
        }
        return send(request);
    }

    /** The body as bytes, for a binary response such as the Workpack print PDF (BPA-10). */
    public HttpResponse<byte[]> getBytes(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** HEAD, to check that a rule on a path holds for every method (BPA-10 review). */
    public HttpResponse<Void> head(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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

    /** PUT with the CSRF token (BPA-50). */
    public HttpResponse<String> put(String path, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json));
        String token = cookie("XSRF-TOKEN");
        if (token != null) {
            request.header("X-XSRF-TOKEN", token);
        }
        return send(request);
    }

    /** DELETE with the CSRF token (BPA-50). */
    public HttpResponse<String> delete(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).DELETE();
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
