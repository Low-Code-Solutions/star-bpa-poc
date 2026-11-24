package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.domain.WorkPackInput;
import com.lcs.star.bpa.domain.WorkPackValidator;
import com.lcs.star.bpa.persistence.ReferenceRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository.SavedWorkPack;
import com.lcs.star.bpa.task.TaskService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code save-workpack} (design pack N2, N3; D56, D65) on two elements:
 * <ul>
 * <li>{@code ServiceTask_SaveWorkPack} (BPA-6): re-checks the Create Work Pack data and saves the Work Pack (Draft).</li>
 * <li>{@code ServiceTask_SaveWorkPackCorrections} (BPA-23): re-checks a Confirm Workpack correction and applies it in place
 * to the Draft Work Pack (data model F13), with audit rows (D64).</li>
 * </ul>
 * On any message both throw BPMN error {@code WORKPACK_INVALID} with {@code validationMessages}, so the boundary event returns
 * the task (G2). On success both complete with {@code workpackId}, {@code workpackNumber}, an empty
 * {@code validationMessages} (cleared after a successful save, BPA-23) and the saved {@code phases} and {@code materials}, each
 * material line with its {@code id}, so the Confirm screen can round-trip them (data model A4; Ahsan, 4 Nov 2026).
 */
@Component
public class SaveWorkPackWorker {

    static final String ELEMENT = "ServiceTask_SaveWorkPack";
    static final String CORRECTIONS = "ServiceTask_SaveWorkPackCorrections";
    static final String ERROR_CODE = "WORKPACK_INVALID";
    /** B12: who a worker writes as when no user is in context. */
    static final String SERVICE_USER = "bpa-service";
    static final String BAD_DATE = "Date to complete must be a date (YYYY-MM-DD)"; // ASSUMPTION

    private static final Logger LOG = LoggerFactory.getLogger(SaveWorkPackWorker.class);

    private final WorkPackRepository workPacks;
    private final ReferenceRepository references;
    private final Clock clock;
    private final ZoneId businessZone;

    public SaveWorkPackWorker(WorkPackRepository workPacks, ReferenceRepository references, Clock clock, ZoneId businessZone) {
        this.workPacks = workPacks;
        this.references = references;
        this.clock = clock;
        this.businessZone = businessZone;
    }

    @JobWorker(type = "save-workpack", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        if (CORRECTIONS.equals(job.getElementId())) {
            correct(client, job);
            return;
        }
        // D65, S7: a retry after a committed save gets that Work Pack back, before any check. Re-validating could now fail
        // (a "today" date gone past at midnight) and loop the task back while the Work Pack exists (BPA-6 review).
        var existing = workPacks.find(job.getProcessInstanceKey());
        if (existing.isPresent()) {
            LOG.info("save-workpack job {} retried: Work Pack {} already saved", job.getKey(), existing.get().workpackNumber());
            complete(client, job, existing.get());
            return;
        }
        Map<String, Object> variables = job.getVariablesAsMap();
        List<String> messages = new ArrayList<>();
        WorkPackInput input = read(variables, messages);
        messages.addAll(rules(input, messages));
        if (rejected(client, job, messages)) {
            return;
        }
        String username = username(variables);
        SavedWorkPack saved = workPacks.save(job.getProcessInstanceKey(), input, username);
        LOG.info("Work Pack {} saved for instance {} by {}", saved.workpackNumber(), job.getProcessInstanceKey(), username);
        complete(client, job, saved);
    }

    /**
     * BPA-23: a Confirm Workpack correction. The Work Pack must exist (else an exception: Camunda's default retries, then an
     * incident) and be Draft (else an incident with no retries; after Released no Confirm task exists, D8). The same checks as
     * at create, plus: a material line id must be one of this Work Pack's lines. Applying the same correction twice changes
     * nothing, so a retried job is harmless (D65).
     */
    private void correct(JobClient client, ActivatedJob job) {
        SavedWorkPack wp = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + CORRECTIONS + ")"));
        String status = workPacks.status(wp.id()).orElseThrow();
        if (!"Draft".equals(status)) {
            String reason = wp.workpackNumber() + " is " + status + "; corrections are closed (D8)"; // ASSUMPTION text
            LOG.warn("save-workpack job {} refused: {}", job.getKey(), reason);
            client.newFailCommand(job.getKey()).retries(0).errorMessage(reason).send().join();
            return;
        }
        Map<String, Object> variables = job.getVariablesAsMap();
        List<String> messages = new ArrayList<>();
        WorkPackInput input = read(variables, messages);
        // A correction that asks for exactly what is saved completes without re-checking (Ahsan, 5 Nov 2026; BPA-23 review):
        // a retried job whose first run committed, or a confirm of unchanged data. Re-checking could now fail (D20 after
        // Riyadh midnight) and return the task although the correction is saved.
        if (messages.isEmpty() && workPacks.alreadySaved(wp.id(), input)) {
            LOG.info("save-workpack job {}: correction of {} already saved", job.getKey(), wp.workpackNumber());
            complete(client, job, wp);
            return;
        }
        messages.addAll(rules(input, messages));
        var lineIds = workPacks.materialLineIds(wp.id());
        Set<String> seen = new HashSet<>();
        for (WorkPackInput.Material material : input.materials()) {
            if (material.id() == null) {
                continue;
            }
            if (!lineIds.contains(material.id())) {
                messages.add("Material line " + material.id() + " is not part of this Work Pack"); // ASSUMPTION text
            } else if (!seen.add(material.id())) {
                messages.add("Material line " + material.id() + " is listed twice"); // ASSUMPTION text (BPA-23 review)
            }
        }
        if (rejected(client, job, messages)) {
            return;
        }
        String username = username(variables);
        workPacks.correct(wp.id(), input, username);
        LOG.info("Work Pack {} corrected for instance {} by {}", wp.workpackNumber(), job.getProcessInstanceKey(), username);
        complete(client, job, wp);
    }

    /** The validator's messages; an unreadable date has its own message instead of "is required". */
    private List<String> rules(WorkPackInput input, List<String> readMessages) {
        List<String> rules = new ArrayList<>(WorkPackValidator.validate(input, LocalDate.ofInstant(clock.instant(), businessZone), known()));
        if (readMessages.contains(BAD_DATE)) {
            rules.remove("Date to complete is required"); // the date is there, only unreadable
        }
        return rules;
    }

    /** Any message: BPMN error WORKPACK_INVALID with validationMessages, so the task comes back showing them (G2, D56). */
    private boolean rejected(JobClient client, ActivatedJob job, List<String> messages) {
        if (messages.isEmpty()) {
            return false;
        }
        List<String> distinct = messages.stream().distinct().toList();
        LOG.info("Work Pack of instance {} rejected ({}): {}", job.getProcessInstanceKey(), job.getElementId(), distinct);
        client.newThrowErrorCommand(job.getKey()).errorCode(ERROR_CODE).errorMessage(String.join("; ", distinct))
                .variables(Map.of("validationMessages", distinct)).send().join();
        return true;
    }

    /** D63: the user who completed the task (set by the task API), else the service user (B12). */
    private static String username(Map<String, Object> variables) {
        Object completedBy = variables.get(TaskService.COMPLETED_BY);
        return completedBy instanceof String s && !s.isBlank() ? s : SERVICE_USER;
    }

    private void complete(JobClient client, ActivatedJob job, SavedWorkPack saved) {
        Map<String, Object> output = new java.util.LinkedHashMap<>(workPacks.lines(saved.id()));
        output.put("workpackId", saved.id().toString());
        output.put("workpackNumber", saved.workpackNumber());
        output.put("validationMessages", List.of());
        client.newCompleteCommand(job.getKey()).variables(output).send().join();
    }

    private WorkPackValidator.KnownRefs known() {
        return new WorkPackValidator.KnownRefs() {
            @Override
            public boolean activity(String activityRef) {
                return references.activityExists(activityRef);
            }

            @Override
            public boolean drawing(String drawingRef) {
                return references.drawingExists(drawingRef);
            }

            @Override
            public boolean material(String materialRef) {
                return references.materialExists(materialRef);
            }

            @Override
            public String activityProject(String activityRef) {
                return references.activityProject(activityRef);
            }

            @Override
            public boolean department(String departmentRef) {
                return references.departmentExists(departmentRef);
            }
        };
    }

    /** The task's variables as typed input; values of the wrong type are read as missing, and a bad date adds a message. */
    static WorkPackInput read(Map<String, Object> variables, List<String> messages) {
        LocalDate completionDate = null;
        Object date = variables.get("completionDate");
        if (date instanceof String s && !s.isBlank()) {
            try {
                completionDate = LocalDate.parse(s);
            } catch (DateTimeParseException e) {
                messages.add(BAD_DATE);
            }
        }
        List<WorkPackInput.Phase> phases = new ArrayList<>();
        for (Map<?, ?> row : rows(variables.get("phases"))) {
            phases.add(new WorkPackInput.Phase(text(row.get("phase")), number(row.get("manhours"))));
        }
        List<WorkPackInput.Material> materials = new ArrayList<>();
        for (Map<?, ?> row : rows(variables.get("materials"))) {
            String id = text(row.get("id")); // the saved line's id, round-tripped by the Confirm screen (BPA-23, A4)
            materials.add(new WorkPackInput.Material(text(row.get("materialRef")), text(row.get("phase")), id == null || id.isBlank() ? null : id));
        }
        return new WorkPackInput(text(variables.get("activityRef")), text(variables.get("drawingRef")),
                text(variables.get("departmentRef")), completionDate, phases, materials, text(variables.get("projectRef")));
    }

    private static List<Map<?, ?>> rows(Object value) {
        List<Map<?, ?>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add(map);
                }
            }
        }
        return rows;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    /** Numbers arrive as Integer, Long or Double from Camunda's JSON; toString keeps 0.001 as 0.001. */
    private static BigDecimal number(Object value) {
        if (value instanceof Number || value instanceof String) {
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
