package com.lcs.star.bpa.worker;

import com.lcs.star.bpa.persistence.ReleaseInputsRepository;
import com.lcs.star.bpa.persistence.WorkPackRepository;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job type {@code evaluate-release-inputs} (BPA-8) on {@code ServiceTask_EvaluateReleaseInputs}: reads the POC database and
 * sets {@code materialsReserved} and {@code drawingApproved}; Decision_WorkpackReleaseCheck decides (D24).
 *
 * <p>Built with the new-job-worker skill. It only reads, so it is idempotent by nature (D65, S7): the same data gives the same
 * result on a retry and nothing is written, audited or stamped. No input to validate, so no BPMN error. Retries and
 * incidents are Camunda's defaults (S7): a job whose instance has no Work Pack throws, and ends as an incident after three tries.
 */
@Component
public class EvaluateReleaseInputsWorker {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluateReleaseInputsWorker.class);

    private final WorkPackRepository workPacks;
    private final ReleaseInputsRepository inputs;

    public EvaluateReleaseInputsWorker(WorkPackRepository workPacks, ReleaseInputsRepository inputs) {
        this.workPacks = workPacks;
        this.inputs = inputs;
    }

    @JobWorker(type = "evaluate-release-inputs", autoComplete = false)
    public void handle(JobClient client, ActivatedJob job) {
        var workPack = workPacks.find(job.getProcessInstanceKey()).orElseThrow(() -> new IllegalStateException(
                "No Work Pack for process instance " + job.getProcessInstanceKey() + " (" + job.getElementId() + ")"));
        boolean materialsReserved = inputs.materialsReserved(workPack.id());
        boolean drawingApproved = inputs.drawingApproved(workPack.id());

        LOG.info("Release inputs of {}: materialsReserved={}, drawingApproved={} (job {})",
                workPack.workpackNumber(), materialsReserved, drawingApproved, job.getKey());
        client.newCompleteCommand(job.getKey())
                .variables(Map.of("materialsReserved", materialsReserved, "drawingApproved", drawingApproved))
                .send().join();
    }
}
