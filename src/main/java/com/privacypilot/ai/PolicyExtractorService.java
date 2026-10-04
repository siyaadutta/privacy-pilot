package com.privacypilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.privacypilot.llm.LlmClient;

/**
 * AI function #1: extract structured deletion info from a privacy policy.
 *
 * <p>Contract returned to business logic:
 * {@code { deletionSupported, deletionMethod, contact, requiredInformation[], responseDeadline }}.
 *
 * <p>The LLM's raw text never drives workflow directly: it is parsed and
 * validated into a {@link PolicyExtractionResult} here. If the LLM is offline,
 * fails, or returns malformed JSON, a deterministic keyword-based fallback is
 * used. Crucially, if the policy text contains no deletion/removal language, the
 * result is {@code deletionSupported = false} rather than a hallucinated method.
 */
@Service
public class PolicyExtractorService {

    private static final Logger log = LoggerFactory.getLogger(PolicyExtractorService.class);

    private static final String SYSTEM = """
            You extract data-deletion information from privacy policies.
            Respond ONLY with a JSON object of the exact shape:
            {"deletionSupported": boolean, "deletionMethod": string|null,
             "contact": string|null, "requiredInformation": string[],
             "responseDeadline": string|null}.
            If the policy does not describe a way to delete or remove personal
            data, set deletionSupported=false and leave the other fields null/[].
            Never invent a contact or method that is not in the text.""";

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public PolicyExtractorService(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public PolicyExtractionResult extract(String policyText) {
        if (policyText == null || policyText.isBlank()) {
            return PolicyExtractionResult.notSupported("FALLBACK");
        }
        Optional<String> raw = llm.complete(SYSTEM, "Privacy policy text:\n" + policyText);
        if (raw.isPresent()) {
            Optional<PolicyExtractionResult> parsed = parse(raw.get());
            if (parsed.isPresent()) {
                return parsed.get();
            }
            log.warn("Policy extractor: LLM output failed validation; using deterministic fallback.");
        }
        return fallback(policyText);
    }

    /**
     * Parse and validate the LLM's JSON output. Returns empty if malformed or
     * missing the required boolean field — the caller then falls back.
     */
    Optional<PolicyExtractionResult> parse(String rawJson) {
        try {
            JsonNode node = mapper.readTree(extractJsonObject(rawJson));
            if (!node.isObject() || !node.has("deletionSupported")
                    || !node.get("deletionSupported").isBoolean()) {
                return Optional.empty();
            }
            boolean supported = node.get("deletionSupported").asBoolean();
            if (!supported) {
                return Optional.of(PolicyExtractionResult.notSupported("LLM"));
            }
            String method = textOrNull(node, "deletionMethod");
            String contact = textOrNull(node, "contact");
            String deadline = textOrNull(node, "responseDeadline");
            List<String> required = new ArrayList<>();
            JsonNode req = node.get("requiredInformation");
            if (req != null && req.isArray()) {
                req.forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        required.add(n.asText());
                    }
                });
            }
            return Optional.of(new PolicyExtractionResult(true, method, contact, required, deadline, "LLM"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Deterministic fallback: look for deletion/removal language. If none is
     * present, deletion is not supported. This guarantees we never hallucinate a
     * deletion process for a policy that does not mention one.
     *
     * <p>Two conservative rules, to avoid the overclaims a naive keyword scan
     * would make:
     * <ul>
     *   <li><b>Negation:</b> phrasing like "we do not delete personal data on
     *       request" mentions deletion but <em>denies</em> it — treated as not
     *       supported.</li>
     *   <li><b>No invented method:</b> a deletion method is only asserted when
     *       the text actually evidences one (an email address, or explicit
     *       form/portal/online wording). We never default to WEB_FORM merely
     *       because no email was found; if the method is unclear it is left
     *       null.</li>
     * </ul>
     */
    PolicyExtractionResult fallback(String policyText) {
        String lower = policyText.toLowerCase(Locale.ROOT);
        boolean mentionsDeletion =
                lower.contains("delete") || lower.contains("deletion")
                        || lower.contains("erase") || lower.contains("erasure")
                        || lower.contains("remove your") || lower.contains("removal of your")
                        || lower.contains("removal request") || lower.contains("right to be forgotten");
        if (!mentionsDeletion) {
            return PolicyExtractionResult.notSupported("FALLBACK");
        }
        // Negation guard: deletion is mentioned but explicitly refused.
        boolean deniesDeletion =
                lower.contains("do not delete") || lower.contains("does not delete")
                        || lower.contains("cannot delete") || lower.contains("will not delete")
                        || lower.contains("do not erase") || lower.contains("not able to delete")
                        || lower.contains("no right to erasure");
        if (deniesDeletion) {
            return PolicyExtractionResult.notSupported("FALLBACK");
        }

        String contact = findEmail(policyText);
        // Only assert a method the text actually evidences; otherwise leave null.
        String method = null;
        if (contact != null) {
            method = "EMAIL";
        } else if (lower.contains("form") || lower.contains("portal") || lower.contains("online")
                || lower.contains("website") || lower.contains("account settings")
                || lower.contains("settings page")) {
            method = "WEB_FORM";
        }
        return new PolicyExtractionResult(true, method, contact,
                List.of("full name", "account email"), null, "FALLBACK");
    }

    private static String findEmail(String text) {
        var matcher = java.util.regex.Pattern
                .compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
                .matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }

    /**
     * Some models wrap JSON in prose or code fences. Extract the outermost JSON
     * object if present; otherwise return the input unchanged.
     */
    /**
     * Extract the first complete top-level JSON object from model output that may
     * be wrapped in prose or ```json code fences.
     *
     * <p>Improvement over a naive first-'{' / last-'}' slice: this scans with a
     * brace-depth counter that <em>respects string literals and escape
     * sequences</em>, so braces that appear inside string values (e.g. an email
     * body quoted back to us) do not corrupt the boundaries, and trailing prose or
     * a second object after the first are ignored. Returns the input unchanged if
     * no balanced object is found (the caller's JSON parse will then fail cleanly
     * and trigger the deterministic fallback).
     */
    static String extractJsonObject(String raw) {
        if (raw == null) {
            return "";
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return raw;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1); // first complete object
                }
            }
        }
        return raw; // unbalanced — let the JSON parser reject it
    }
}
