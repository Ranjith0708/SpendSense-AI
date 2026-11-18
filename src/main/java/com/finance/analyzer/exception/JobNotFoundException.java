package com.finance.analyzer.exception;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String jobId) { super("Unknown or expired analysis job: " + jobId); }
}
