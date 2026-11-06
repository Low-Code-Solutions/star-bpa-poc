package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.AssignmentFilesApi;
import com.lcs.star.bpa.api.generated.model.AssignmentFileResult;
import com.lcs.star.bpa.assignment.AssignmentFileService;
import com.lcs.star.bpa.persistence.AssignmentFileRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
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
 * The Worker assignment file API (BPA-12; D58, 09 S6, S18): {@code POST /api/v1/assignment-files} records an upload and routes it
 * (AssignmentFileService), {@code GET .../{id}/result} gives the result as JSON or, by the Accept header, as CSV
 * (AssignmentFileResultCsvConverter), and {@code GET .../template} serves the official template. SecurityConfig lets only
 * {@code production-team} through, for every method (403 otherwise; group ASSUMPTION in the story).
 */
@RestController
public class AssignmentFileController implements AssignmentFilesApi {

    private static final String TEMPLATE = "templates/worker-assignment-template.xlsx";

    private final AssignmentFileService service;
    private final AssignmentFileRepository repository;

    public AssignmentFileController(AssignmentFileService service, AssignmentFileRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public ResponseEntity<AssignmentFileResult> uploadAssignmentFile(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("The uploaded file could not be read from the request", e);
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "assignment.xlsx" : file.getOriginalFilename();
        String username = SecurityContextHolder.getContext().getAuthentication().getName(); // D63: the Camunda username
        UUID id = service.upload(name, bytes, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.result(id).orElseThrow());
    }

    @Override
    public ResponseEntity<AssignmentFileResult> getAssignmentFileResult(UUID assignmentFileId) {
        return ResponseEntity.ok(repository.result(assignmentFileId).orElseThrow(() -> new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Assignment file not found"), null)));
    }

    @Override
    public ResponseEntity<Resource> downloadAssignmentTemplate() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"worker-assignment-template.xlsx\"")
                .body(new ClassPathResource(TEMPLATE));
    }

    /** A request with no file part: the contract's 400 "File is required" (x-assumption text). */
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> fileIsRequired(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "File is required"));
    }
}
