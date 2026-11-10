package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.adapter.CamundaGroupClient;
import com.lcs.star.bpa.persistence.NotificationRepository;
import com.lcs.star.bpa.persistence.NotificationRepository.LiveWorkPack;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code find-revision-recipients} on {@code ServiceTask_FindRecipients} of Process_PPC_DrawingRevisionNotice (BPA-16; D11, D43 as simplified by
 * the PO decision of 2 Nov 2026, D46, D63). Who is notified about a revision of a drawing: the users of the Camunda groups workpack-owner and
 * project-engineer, once each, but only when the drawing is linked to at least one Work Pack that is Released or In Progress. Each recipient is a Camunda
 * username with the email address from app_user (D63); a user with no app_user row is skipped with a warning.
 *
 * <p>Completes with {@code recipients} (list of {username, email}; empty when nobody is to be told, which Gateway_HasRecipients sends to
 * EndEvent_NoRecipients) and {@code workpacks} (list of {id, number}, the Work Packs the notices are about). It only reads, so a retry is safe.
 * Retries and backoff are Camunda's defaults (S7, ADR 0007). Without GROUP READ on a group Camunda answers an empty list, so an empty group is logged
 * with that hint (probe Bpa16GroupReadChecksTest).
 */
@Component
public class FindRevisionRecipientsWorker {

    /** The groups that receive a drawing-revision notice (story BPA-16). */
    static final List<String> GROUPS = List.of("workpack-owner", "project-engineer");

    private static final Logger LOG = LoggerFactory.getLogger(FindRevisionRecipientsWorker.class);

    private final NotificationRepository notifications;
    private final CamundaGroupClient groups;

    public FindRevisionRecipientsWorker(NotificationRepository notifications, CamundaGroupClient groups) {
        this.notifications = notifications;
        this.groups = groups;
    }

    @JobWorker(type = "find-revision-recipients", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        if (!(variables.get("drawingRef") instanceof String drawingRef) || !(variables.get("revision") instanceof String revision)) {
            LOG.warn("find-revision-recipients job {} (instance {}): the message carried no drawingRef and revision", job.getKey(), job.getProcessInstanceKey());
            client.newFailCommand(job.getKey()).retries(0).errorMessage("the message carried no drawingRef and revision").send().join();
            return;
        }
        List<LiveWorkPack> workPacks = notifications.liveWorkPacksOn(drawingRef);
        List<Map<String, String>> recipients = new ArrayList<>();
        if (!workPacks.isEmpty()) {
            Set<String> usernames = new LinkedHashSet<>();
            for (String group : GROUPS) {
                List<String> members = groups.usernames(group);
                if (members.isEmpty()) {
                    LOG.warn("Group {} returned no members (if it has members, bpa-service lacks GROUP READ on it)", group);
                }
                usernames.addAll(members);
            }
            Map<String, String> emails = notifications.emails(usernames);
            for (String username : usernames) {
                String email = emails.get(username);
                if (email == null) {
                    LOG.warn("{} has no app_user row, so no email address: not notified about {} {}", username, drawingRef, revision);
                    continue;
                }
                Map<String, String> recipient = new LinkedHashMap<>();
                recipient.put("username", username);
                recipient.put("email", email);
                recipients.add(recipient);
            }
        }
        if (recipients.isEmpty()) {
            LOG.info("No recipients for {} {}", drawingRef, revision);
        }
        List<Map<String, String>> notified = workPacks.stream().map(w -> Map.of("id", w.id().toString(), "number", w.number())).toList();
        LOG.info("find-revision-recipients job {}: {} {} goes to {} recipient(s) about {} Work Pack(s)", job.getKey(), drawingRef, revision,
                recipients.size(), notified.size());
        client.newCompleteCommand(job.getKey()).variables(Map.of("recipients", recipients, "workpacks", notified)).send().join();
    }
}
