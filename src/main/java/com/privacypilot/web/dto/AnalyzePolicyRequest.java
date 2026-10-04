package com.privacypilot.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to analyze a privacy policy text into structured deletion info.
 * Either provide raw policyText, or a known companyId whose policy is on file.
 */
public record AnalyzePolicyRequest(
        Long companyId,
        @NotBlank String policyText) {
}
