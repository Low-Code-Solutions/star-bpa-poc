package com.lcs.star.bpa.drawing;

import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.persistence.DrawingRevisionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receives a DrawingRevised event, the POC's stand-in for D365 Document Control (BPA-16; D41, D45, D46, D53; 09 G6). In one transaction it records the
 * revision, applies the Superseded status the event reports for the previous revision (never decided here, D45), and publishes the message that
 * starts Process_PPC_DrawingRevisionNotice. The message goes out before the commit: if the engine cannot take it, nothing is recorded and the caller
 * answers 503, so D365 can send the event again and it is not "already received". supersededRevision travels only as a process variable (D68).
 */
@Service
public class DrawingRevisionService {

    public enum Outcome { CREATED, ALREADY_RECEIVED, DRAWING_NOT_FOUND }

    static final String MESSAGE = "DrawingRevised";

    private static final Logger LOG = LoggerFactory.getLogger(DrawingRevisionService.class);

    private final DrawingRevisionRepository revisions;
    private final CamundaProcessClient camunda;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DrawingRevisionService(DrawingRevisionRepository revisions, CamundaProcessClient camunda, TransactionTemplate transaction, Clock clock) {
        this.revisions = revisions;
        this.camunda = camunda;
        this.transaction = transaction;
        this.clock = clock;
    }

    public Outcome receive(String drawingRef, String revision, String status, Instant registeredAt, String supersededRevision) {
        if (!revisions.drawingExists(drawingRef)) {
            return Outcome.DRAWING_NOT_FOUND;
        }
        return transaction.execute(tx -> {
            Instant now = clock.instant();
            if (!revisions.insertIfNew(drawingRef, revision, status, registeredAt, now)) {
                return Outcome.ALREADY_RECEIVED;
            }
            boolean named = supersededRevision != null && !supersededRevision.isBlank();
            if (named && !revisions.markSuperseded(drawingRef, supersededRevision, now)) {
                LOG.warn("Superseded revision {} not found for {}", supersededRevision, drawingRef);
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("drawingRef", drawingRef);
            variables.put("revision", revision);
            variables.put("status", status);
            variables.put("registeredAt", registeredAt.toString());
            if (named) {
                variables.put("supersededRevision", supersededRevision);
            }
            // One notice process per drawing and revision: a revision never waits behind another one of the same drawing.
            if (!camunda.correlate(MESSAGE, drawingRef + "/" + revision, variables)) {
                throw new EngineUnavailableException("Camunda did not start the notice process for " + drawingRef + " " + revision);
            }
            return Outcome.CREATED;
        });
    }
}
