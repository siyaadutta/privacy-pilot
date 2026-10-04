package com.privacypilot.ai;

import java.util.List;

/**
 * Structured output of the policy extractor.
 *
 * @param deletionSupported   whether the policy actually describes a deletion/removal process
 * @param deletionMethod      e.g. EMAIL, WEB_FORM, POSTAL, or null/UNKNOWN
 * @param contact             contact point (email/URL) or null
 * @param requiredInformation fields the user must supply
 * @param responseDeadline    stated response deadline (free text) or null
 * @param source              how this result was produced (LLM or FALLBACK), for auditability
 */
public record PolicyExtractionResult(
        boolean deletionSupported,
        String deletionMethod,
        String contact,
        List<String> requiredInformation,
        String responseDeadline,
        String source) {

    public static PolicyExtractionResult notSupported(String source) {
        return new PolicyExtractionResult(false, null, null, List.of(), null, source);
    }
}
