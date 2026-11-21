package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.VarianceAlertsApi;
import com.lcs.star.bpa.api.generated.model.VarianceAlert;
import com.lcs.star.bpa.persistence.VarianceAlertRepository;
import com.lcs.star.bpa.persistence.VarianceAlertRepository.Mine;
import com.lcs.star.bpa.progress.VarianceMessages;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me/variance-alerts} (BPA-64; E2-14): the session user's in-system variance alerts, newest first. The user is the session's, never a parameter, so
 * nobody reads another user's alerts. Any logged-in persona may ask and gets only its own alerts (BPA-65), so a persona with none gets an empty list. The text is rebuilt from the
 * stored figures with {@link VarianceMessages}, so it is the text that was sent.
 */
@RestController
public class VarianceAlertController implements VarianceAlertsApi {

    private final VarianceAlertRepository alerts;

    public VarianceAlertController(VarianceAlertRepository alerts) {
        this.alerts = alerts;
    }

    @Override
    public ResponseEntity<List<VarianceAlert>> listMyVarianceAlerts() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        List<VarianceAlert> mine = alerts.inSystemOf(username).stream().map(VarianceAlertController::map).toList();
        return ResponseEntity.ok(mine);
    }

    private static VarianceAlert map(Mine alert) {
        String message = VarianceAlertRepository.COST.equals(alert.alertType())
                ? VarianceMessages.cost(alert.cbsCodeRef(), alert.variancePct(), alert.actualValue(), alert.baselineValue(), alert.alertDate())
                : VarianceMessages.manhour(alert.cbsCodeRef(), alert.variancePct(), alert.actualValue(), alert.baselineValue(), alert.weekEnding());
        VarianceAlert out = new VarianceAlert(alert.alertDate(), alert.cbsCodeRef(), VarianceAlert.AlertTypeEnum.fromValue(alert.alertType()), message,
                OffsetDateTime.ofInstant(alert.sentAt(), ZoneOffset.UTC));
        out.setWeekEnding(alert.weekEnding());
        return out;
    }
}
