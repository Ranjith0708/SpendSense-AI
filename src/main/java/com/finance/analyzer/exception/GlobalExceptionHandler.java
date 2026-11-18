package com.finance.analyzer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    public record ErrorResponse(String message) {}

    @ExceptionHandler(StatementParseException.class)
    public ResponseEntity<ErrorResponse> parse(StatementParseException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(AiProcessingException.class)
    public ResponseEntity<ErrorResponse> ai(AiProcessingException e) {
        log.error("AI failure", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ErrorResponse> job(JobNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> size(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorResponse("File is larger than 20 MB."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> missing(NoResourceFoundException e) {
        return ResponseEntity.notFound().build();
    }

    /** An idle event stream hit the server's async timeout. The browser reconnects and the job replays, so this is not an error. */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> asyncTimeout(AsyncRequestTimeoutException e) {
        log.debug("async_request_timeout (client will reconnect)");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> other(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Something went wrong while analysing the statement."));
    }
}
