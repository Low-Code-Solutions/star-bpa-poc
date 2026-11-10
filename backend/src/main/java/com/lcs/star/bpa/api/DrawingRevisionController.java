package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.DrawingRevisionsApi;
import com.lcs.star.bpa.api.generated.model.DrawingRevisionEvent;
import com.lcs.star.bpa.api.generated.model.Message;
import com.lcs.star.bpa.drawing.DrawingRevisionService;
import com.lcs.star.bpa.drawing.DrawingRevisionService.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/drawing-revisions}: the POC's stand-in for the D365 Document Control event DrawingRevised (BPA-16; D41, 09 S20, S28). Not
 * restricted: no session, group or API key, and no CSRF token (SecurityConfig). 201 new revision; 200 "Revision already received" for a duplicate
 * (D46); 404 "Drawing not found"; 400 "Unknown drawing status" (D60) or a missing field; 503 when the engine cannot take the message (nothing is
 * recorded then). What happens to the revision is in {@link DrawingRevisionService}.
 */
@RestController
public class DrawingRevisionController implements DrawingRevisionsApi {

    private static final Logger LOG = LoggerFactory.getLogger(DrawingRevisionController.class);

    static final String UNKNOWN_STATUS = "Unknown drawing status";
    static final String REQUIRED = "drawingRef, revision, status and registeredAt are required";

    private final DrawingRevisionService service;

    public DrawingRevisionController(DrawingRevisionService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Message> receiveDrawingRevision(DrawingRevisionEvent event) {
        Outcome outcome;
        try {
            outcome = service.receive(event.getDrawingRef(), event.getRevision(), event.getStatus().getValue(), event.getRegisteredAt().toInstant(),
                    event.getSupersededRevision());
        } catch (EngineUnavailableException e) {
            LOG.warn("Drawing revision {} {} not recorded: {}", event.getDrawingRef(), event.getRevision(), e.getMessage());
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The process engine is not available right now; try again later"), null);
        }
        return switch (outcome) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).build();
            case ALREADY_RECEIVED -> ResponseEntity.ok(new Message("Revision already received"));
            case DRAWING_NOT_FOUND -> throw new ErrorResponseException(HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Drawing not found"), null);
        };
    }

    /** A status outside the contract's list fails when the body is read (the generated enum): "Unknown drawing status" (D60); other unreadable bodies are a plain 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        return badRequest(cause.startsWith("Unexpected value") ? UNKNOWN_STATUS : "The request body is not a valid drawing revision event");
    }

    /** A required field is missing (generated @NotNull; the contract's x-assumption text). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> missingField(MethodArgumentNotValidException e) {
        return badRequest(REQUIRED);
    }

    private static ResponseEntity<ProblemDetail> badRequest(String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
    }
}
