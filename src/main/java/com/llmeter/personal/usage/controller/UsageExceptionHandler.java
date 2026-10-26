package com.llmeter.personal.usage.controller;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UsageController.class)
public class UsageExceptionHandler {

    @ExceptionHandler(ProviderCollectionException.class)
    public ProblemDetail handleProviderCollectionException() {
        final var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Usage data is temporarily unavailable. Please try again later."
        );
        problem.setTitle("Usage collection unavailable");
        return problem;
    }
}
