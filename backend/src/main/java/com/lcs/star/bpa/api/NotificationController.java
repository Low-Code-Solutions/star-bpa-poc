package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.NotificationsApi;
import com.lcs.star.bpa.api.generated.model.Notification;
import com.lcs.star.bpa.persistence.NotificationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me/notifications}: the logged-in user's drawing-revision notices (BPA-16; D44, 09 S6). The only input is the session user's name:
 * the method takes no parameter, so no query string can select another user's notices. Any logged-in persona may call it.
 */
@RestController
public class NotificationController implements NotificationsApi {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    public ResponseEntity<List<Notification>> listMyNotifications() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(notifications.noticesOf(username).stream()
                .map(n -> new Notification(n.workpackNumber(), n.drawingRef(), n.revision(), OffsetDateTime.ofInstant(n.sentAt(), ZoneOffset.UTC)))
                .toList());
    }
}
