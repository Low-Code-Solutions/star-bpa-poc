package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.config.ClockConfig;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code save-workpack} on {@code ServiceTask_SaveWorkPack} (BPA-6; design pack N2; D56, D65). Re-checks the Create
 * Work Pack data; on any message throws BPMN error {@code WORKPACK_INVALID} with {@code validationMessages}, so
 * BoundaryEvent_WorkPackInvalid returns the task (G2). Otherwise saves the Work Pack (Draft) and completes the job with
 * {@code workpackId}, {@code workpackNumber} and an empty {@code validationMessages} (BPA-23: cleared after a successful save).
 *
 * <p>The same job type serves {@code ServiceTask_SaveWorkPackCorrections} (BPA-23). Until BPA-23 builds it, such a job is
 * failed with no retries, so Operate shows an incident and nothing is saved (Ahsan, 4 Nov 2026).
 */
@Component
public class SaveWorkPackWorker {

    static final String ELEMENT = "ServiceTask_SaveWorkPack";
    static final String ERROR_CODE = "WORKPACK_INVALID";
    static final String CORRECTIONS_NOT_BUILT = "Save corrections is built by BPA-23";
    /** B12: who a worker writes as when no user is in context. */
    static final String SERVICE_USER = "bpa-service";

    private static final Logger LOG = LoggerFactory.getLogger(SaveWorkPackWorker.class);

    private final WorkPackRepository workPacks;
    private final ReferenceRepository references;
    private final Clock clock;

    public SaveWorkPackWorker(WorkPackRepository workPacks, ReferenceRepository references, Clock clock) {
        this.workPacks = workPacks;
        this.references = references;
        this.clock = clock;
    }

    @JobWorker(type = "save-workpack", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        if (!ELEMENT.equals(job.getElementId())) {
            LOG.warn("save-workpack job {} from {} refused: {}", job.getKey(), job.getElementId(), CORRECTIONS_NOT_BUILT);
            client.newFailCommand(job.getKey()).retries(0).errorMessage(CORRECTIONS_NOT_BUILT).send().join();
            return;
        }
        Map<String, Object> variables = job.getVariablesAsMap();
        List<String> messages = new ArrayList<>();
        WorkPackInput input = read(variables, messages);
        messages.addAll(WorkPackValidator.validate(input, LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE), known()));
        if (!messages.isEmpty()) {
            List<String> distinct = messages.stream().distinct().toList();
            LOG.info("Work Pack of instance {} rejected: {}", job.getProcessInstanceKey(), distinct);
            client.newThrowErrorCommand(job.getKey()).errorCode(ERROR_CODE).errorMessage(String.join("; ", distinct))
                    .variables(Map.of("validationMessages", distinct)).send().join();
            return;
        }
        Object completedBy = variables.get(TaskService.COMPLETED_BY);
        String username = completedBy instanceof String s && !s.isBlank() ? s : SERVICE_USER;
        SavedWorkPack saved = workPacks.save(job.getProcessInstanceKey(), input, username);
        LOG.info("Work Pack {} saved for instance {} by {}", saved.workpackNumber(), job.getProcessInstanceKey(), username);
        client.newCompleteCommand(job.getKey())
                .variables(Map.of("workpackId", saved.id().toString(), "workpackNumber", saved.workpackNumber(), "validationMessages", List.of()))
                .send().join();
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
                messages.add("Date to complete must be a date (YYYY-MM-DD)"); // ASSUMPTION
            }
        }
        List<WorkPackInput.Phase> phases = new ArrayList<>();
        for (Map<?, ?> row : rows(variables.get("phases"))) {
            phases.add(new WorkPackInput.Phase(text(row.get("phase")), number(row.get("manhours"))));
        }
        List<WorkPackInput.Material> materials = new ArrayList<>();
        for (Map<?, ?> row : rows(variables.get("materials"))) {
            materials.add(new WorkPackInput.Material(text(row.get("materialRef")), text(row.get("phase"))));
        }
        return new WorkPackInput(text(variables.get("activityRef")), text(variables.get("drawingRef")),
                text(variables.get("departmentRef")), completionDate, phases, materials);
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
