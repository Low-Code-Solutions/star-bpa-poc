package com.lcs.star.bpa.api;

import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.api.generated.TimesheetFilesApi;
import com.lcs.star.bpa.api.generated.model.TimesheetFileResult;
import com.lcs.star.bpa.persistence.TimesheetFileRepository;
import com.lcs.star.bpa.timesheet.TimesheetFileService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * The time sheet upload API (BPA-52; E2-D4, D58 and ADR 0009 pattern): {@code POST /api/v1/timesheet-files} records an upload and checks every row
 * (TimesheetFileService), {@code GET .../{id}/result} gives the result as JSON or, by the Accept header, as CSV (TimesheetFileResultCsvConverter), and
 * {@code GET .../template} serves the official template. SecurityConfig lets the supervisor and production-team groups through, for every method (403
 * otherwise, C-E2-5). A file above 5 MB answers 413 (UploadLimitAdvice).
 */
@RestController
public class TimesheetFileController implements TimesheetFilesApi {

    private static final Logger LOG = LoggerFactory.getLogger(TimesheetFileController.class);

    private static final String TEMPLATE = "templates/timesheet-upload-template.xlsx";

    private final TimesheetFileService service;
    private final TimesheetFileRepository repository;

    public TimesheetFileController(TimesheetFileService service, TimesheetFileRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public ResponseEntity<TimesheetFileResult> uploadTimesheetFile(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("The uploaded file could not be read from the request", e);
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "timesheet.xlsx" : file.getOriginalFilename();
        String username = SecurityContextHolder.getContext().getAuthentication().getName(); // D63: the Camunda username
        UUID id = service.upload(name, bytes, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.result(id).orElseThrow());
    }

    @Override
    public ResponseEntity<TimesheetFileResult> getTimesheetFileResult(UUID timesheetFileId) {
        return ResponseEntity.ok(repository.result(timesheetFileId).orElseThrow(() -> new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Upload not found"), null)));
    }

    @Override
    public ResponseEntity<Resource> downloadTimesheetTemplate() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"timesheet-upload-template.xlsx\"")
                .body(new ClassPathResource(TEMPLATE));
    }

    /** A request with no file part: the contract's 400 "File is required" (x-assumption text, as BPA-12). */
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> fileIsRequired(MissingServletRequestPartException e) {
        return problem(HttpStatus.BAD_REQUEST, "File is required");
    }

    /** The engine could not answer the budget decision for a row (BPA-51): the rows before it are saved and logged, the call can be repeated. */
    @ExceptionHandler(EngineUnavailableException.class)
    ResponseEntity<ProblemDetail> engineUnavailable(EngineUnavailableException e) {
        LOG.warn("Time sheet upload stopped: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "The process engine is not available right now; try again later");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
