package com.lcs.star.bpa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.adapter.CamundaIdentityClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Login, sessions, CSRF and the backend's group checks (S5, S6, S26, S27; ADR 0005). React only hides what a user cannot use;
 * this is where it is enforced (403). No form login and no basic auth on the app: {@code POST /api/v1/auth/login} starts the
 * session (AuthController).
 */
@Configuration
public class SecurityConfig {

    /** Process start from the portal: actor {@code time-method-analyst} (D52, 09 S20; BPA-43 Q6). */
    static final String TIME_METHOD_ANALYST = "time-method-analyst";

    /** Reads the Project Hour Journal Header (BPA-7). */
    static final String PPC_TEAM = "ppc-team";

    /** Read the Workpack print (BPA-10; with the Analyst; groups ASSUMPTION in the story). */
    static final String PRODUCTION_PLANNER = "production-planner";
    static final String PRODUCTION_TEAM = "production-team";

    /** Reads the Project Hour Journal Lines with the PPC Team (BPA-14; groups ASSUMPTION in the story, 09 S6). */
    static final String SUPERVISOR = "supervisor";

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper json) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Error dispatches keep their own status instead of turning into a 401 for anonymous callers.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/workpacks").hasAuthority(TIME_METHOD_ANALYST)
                        // BPA-7 (D22): the Header is read by the PPC Team and the Analyst (groups ASSUMPTION in the story).
                        .requestMatchers(HttpMethod.GET, "/api/v1/hour-journal-headers").hasAnyAuthority(PPC_TEAM, TIME_METHOD_ANALYST)
                        // BPA-10: the Workpack print and the list the portal shows are read by the Analyst, the Planner and the
                        // Production Team (groups ASSUMPTION in the story); the backend answers 403 for anyone else (09 S6).
                        // Every method, not only GET: a HEAD request is served by the GET handler and must not skip the rule.
                        .requestMatchers("/api/v1/workpacks/*/print", "/api/v1/workpack-prints")
                                .hasAnyAuthority(TIME_METHOD_ANALYST, PRODUCTION_PLANNER, PRODUCTION_TEAM)
                        // BPA-12: the Worker assignment upload, its result and the template are the Production Team's (D58, 09 S6; group
                        // ASSUMPTION in the story), every method, so HEAD cannot skip the rule (the BPA-10 lesson).
                        .requestMatchers("/api/v1/assignment-files", "/api/v1/assignment-files/**").hasAuthority(PRODUCTION_TEAM)
                        // BPA-14 (D39): the Lines are read by the PPC Team and the Supervisor (groups ASSUMPTION in the story); the backend answers
                        // 403 for anyone else (09 S6). Every method, so a HEAD request cannot skip the rule (the BPA-10 lesson).
                        .requestMatchers("/api/v1/hour-journal-lines").hasAnyAuthority(PPC_TEAM, SUPERVISOR)
                        .anyRequest().authenticated())
                // S26: token in the XSRF-TOKEN cookie (readable by React), sent back as X-XSRF-TOKEN on every state-changing call,
                // the login included. The plain handler lets React send the cookie value unchanged.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .securityContext(context -> context.securityContextRepository(securityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) ->
                                problem(response, json, HttpStatus.UNAUTHORIZED, "Not logged in, or the session has expired"))
                        .accessDeniedHandler((request, response, e) ->
                                problem(response, json, HttpStatus.FORBIDDEN, "Your groups do not allow this call, or the CSRF token is missing")));
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CamundaIdentityClient camundaIdentityClient(@Value("${bpa.camunda.rest-url}") String restUrl) {
        return new CamundaIdentityClient(restUrl);
    }

    static void problem(HttpServletResponse response, ObjectMapper json, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }

    /** Loads the deferred CSRF token on every request so the XSRF-TOKEN cookie is set before React's first POST. */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
