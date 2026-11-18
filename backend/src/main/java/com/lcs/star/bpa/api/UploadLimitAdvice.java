package com.lcs.star.bpa.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * A file above the multipart limit (spring.servlet.multipart.max-file-size, 5 MB) answers 413 "File is larger than 5 MB" (BPA-52, ASSUMPTION as for the
 * assignment files; plan Q8). Global: the Worker assignment upload gets the same answer instead of Spring's default problem.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE) // before Spring's problem-detail handler (spring.mvc.problemdetails.enabled), which would answer "Maximum upload size exceeded"
public class UploadLimitAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "File is larger than 5 MB"));
    }
}
