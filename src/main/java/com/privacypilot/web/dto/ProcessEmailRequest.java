package com.privacypilot.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Simulates an inbound email from a company being fed into the pipeline.
 */
public record ProcessEmailRequest(
        @NotNull Long requestId,
        String subject,
        @NotBlank String body,
        String messageId) {
}
