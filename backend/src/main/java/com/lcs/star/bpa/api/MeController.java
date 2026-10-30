package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.MeApi;
import com.lcs.star.bpa.api.generated.model.Me;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/me}: the username and the Camunda groups read at login (S5, S6). */
@RestController
public class MeController implements MeApi {

    @Override
    public ResponseEntity<Me> getMe() {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(new Me(user.getName(), user.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList()));
    }
}
