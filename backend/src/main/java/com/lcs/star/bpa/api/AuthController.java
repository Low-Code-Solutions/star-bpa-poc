package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import com.lcs.star.bpa.adapter.CamundaIdentityClient.CamundaUser;
import com.lcs.star.bpa.api.generated.AuthApi;
import com.lcs.star.bpa.api.generated.model.LoginRequest;
import com.lcs.star.bpa.api.generated.model.Me;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.client.RestClientException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/login} and {@code /logout} (S5, L4, ADR 0005). The backend checks the password with Camunda basic auth
 * once, reads the groups once and keeps them in the session; the password is not kept. A group change takes effect at the next
 * login (S27).
 */
@RestController
public class AuthController implements AuthApi {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    private final CamundaIdentityClient camunda;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    public AuthController(CamundaIdentityClient camunda, SecurityContextRepository contexts, CsrfTokenRepository csrfTokens,
            HttpServletRequest request, HttpServletResponse response) {
        this.camunda = camunda;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
        this.request = request;
        this.response = response;
    }

    @Override
    public ResponseEntity<Me> login(LoginRequest login) {
        CamundaUser user = verify(login)
                .orElseThrow(() -> new ErrorResponseException(HttpStatus.UNAUTHORIZED,
                        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Wrong username or password"), null));
        List<String> groups = user.groups().stream().sorted().toList();

        // A fresh session for the logged-in user (session fixation).
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true);

        var authentication = UsernamePasswordAuthenticationToken.authenticated(user.username(), null,
                groups.stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        // A new CSRF token for the new session, as Spring's CsrfAuthenticationStrategy would do for a filter-based login (S26).
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);

        return ResponseEntity.ok(new Me(user.username(), groups));
    }

    /** A Camunda that cannot be reached or answers oddly is a 503, never a "wrong password". */
    private Optional<CamundaUser> verify(LoginRequest login) {
        try {
            return camunda.verify(login.getUsername(), login.getPassword());
        } catch (RestClientException | IllegalStateException e) {
            LOG.warn("Login check against Camunda failed: {}", e.toString());
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Login is not available right now; try again later"), null);
        }
    }

    @Override
    public ResponseEntity<Void> logout() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }
}
