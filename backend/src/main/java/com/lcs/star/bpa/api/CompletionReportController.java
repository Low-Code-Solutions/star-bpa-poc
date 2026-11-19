package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.CompletionReportsApi;
import com.lcs.star.bpa.api.generated.model.CompletionReportResult;
import com.lcs.star.bpa.persistence.CompletionReportRepository;
import com.lcs.star.bpa.progress.CompletionReportService;
import com.lcs.star.bpa.task.UploadTaskResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * The completion report API (BPA-59; E2-09): {@code POST /api/v1/completion-reports} logs an upload into the open Upload completion report task and checks
 * every row (CompletionReportService), {@code GET .../{id}/result} gives the result again. SecurityConfig lets only ppc-team through, for every method (403
 * otherwise). A file above 5 MB answers 413 (UploadLimitAdvice). A request with no file part or no task key answers 400 "File is required" (ASSUMPTION, as
 * BPA-52; the missing task key is the framework's 400).
 */
@RestController
public class CompletionReportController implements CompletionReportsApi {

    private static final Logger LOG = LoggerFactory.getLogger(CompletionReportController.class);

    private final CompletionReportService service;
    private final CompletionReportRepository repository;
    private final UploadTaskResolver tasks;

    public CompletionReportController(CompletionReportService service, CompletionReportRepository repository, UploadTaskResolver tasks) {
        this.service = service;
        this.repository = repository;
        this.tasks = tasks;
    }

    @Override
    public ResponseEntity<CompletionReportResult> uploadCompletionReport(MultipartFile file, String taskKey) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("The uploaded file could not be read from the request", e);
        }
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        long instance = tasks.processInstanceOf(taskKey, user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "completion-report.xlsx" : file.getOriginalFilename();
        UUID id = service.upload(instance, name, bytes, user.getName()); // D63: the Camunda username
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.result(id).orElseThrow());
    }

    @Override
    public ResponseEntity<CompletionReportResult> getCompletionReportResult(UUID completionReportId) {
        return ResponseEntity.ok(repository.result(completionReportId).orElseThrow(() -> new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Report not found"), null)));
    }

    /** A request with no file part: the contract's 400 "File is required" (x-assumption text, as BPA-52). */
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> fileIsRequired(MissingServletRequestPartException e) {
        LOG.debug("Completion report upload without a part: {}", e.getRequestPartName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "File is required");
        return ResponseEntity.badRequest().body(problem);
    }
}
