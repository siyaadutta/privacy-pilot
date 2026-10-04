package com.privacypilot.ai;

import com.privacypilot.workflow.Classification;

/**
 * Structured output of the response classifier.
 *
 * @param classification   one of the six categories (UNKNOWN when unsure)
 * @param confidence       0..1 confidence in the classification
 * @param ambiguous        explicit ambiguity flag set by the model or fallback
 * @param referenceNumber  extracted reference/ticket number, or null
 * @param deadline         extracted deadline (free text), or null
 * @param requiredAction   what the company asks the user to do, or null
 * @param rejectionReason  reason for rejection, or null
 * @param rawOutput        raw text the classifier produced (for audit/human review)
 * @param source           LLM or FALLBACK
 */
public record ClassificationResult(
        Classification classification,
        double confidence,
        boolean ambiguous,
        String referenceNumber,
        String deadline,
        String requiredAction,
        String rejectionReason,
        String rawOutput,
        String source) {
}
