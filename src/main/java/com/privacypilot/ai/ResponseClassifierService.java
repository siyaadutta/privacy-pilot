package com.privacypilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.privacypilot.llm.LlmClient;
import com.privacypilot.workflow.Classification;

/**
 * AI function #3: classify an inbound company reply and extract details.
 *
 * <p>Returns a {@link ClassificationResult} with a category, a confidence score
 * (0..1), and an explicit ambiguity flag, plus any extracted reference number,
 * deadline, required action, and rejection reason. The raw model output is always
 * retained on the result for auditability and human review.
 *
 * <p>This service does NOT decide whether to route to human review — it only
 * classifies and reports confidence. The routing decision (compare against the
 * configured threshold) lives in the workflow layer so the policy is centralized.
 */
@Service
public class ResponseClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ResponseClassifierService.class);

    private static final String SYSTEM = """
            You classify replies from companies about data-deletion requests.
            Respond ONLY with JSON of shape:
            {"classification": one of
              ["RECEIVED","COMPLETED","VERIFICATION_REQUIRED",
               "MORE_INFORMATION_REQUIRED","REJECTED","UNKNOWN"],
             "confidence": number between 0 and 1,
             "ambiguous": boolean,
             "referenceNumber": string|null, "deadline": string|null,
             "requiredAction": string|null, "rejectionReason": string|null}.
            If the reply is vague, conflicting, or you are unsure, set a low
            confidence and ambiguous=true rather than guessing a category.""";

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public ResponseClassifierService(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public ClassificationResult classify(String emailBody) {
        if (emailBody == null || emailBody.isBlank()) {
            return new ClassificationResult(Classification.UNKNOWN, 0.0, true,
                    null, null, null, null, "", "FALLBACK");
        }
        Optional<String> raw = llm.complete(SYSTEM, "Reply body:\n" + emailBody);
        if (raw.isPresent()) {
            Optional<ClassificationResult> parsed = parse(raw.get());
            if (parsed.isPresent()) {
                return parsed.get();
            }
            log.warn("Classifier: LLM output failed validation; using deterministic fallback.");
        }
        return fallback(emailBody);
    }

    /**
     * Parse and validate the classifier's JSON. Returns empty if the JSON is
     * malformed or the classification value is not one of the allowed enum names.
     */
    Optional<ClassificationResult> parse(String rawJson) {
        try {
            String json = PolicyExtractorService.extractJsonObject(rawJson);
            JsonNode node = mapper.readTree(json);
            JsonNode cls = node.get("classification");
            if (cls == null || !cls.isTextual()) {
                return Optional.empty();
            }
            Classification classification;
            try {
                classification = Classification.valueOf(cls.asText().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
            double confidence = clamp(node.path("confidence").asDouble(Double.NaN));
            boolean ambiguous = node.path("ambiguous").asBoolean(false);
            // If confidence was absent/unparseable, treat as ambiguous rather than trusting it.
            if (Double.isNaN(confidence)) {
                confidence = 0.0;
                ambiguous = true;
            }

            String referenceNumber = textOrNull(node, "referenceNumber");
            String deadline = textOrNull(node, "deadline");
            String requiredAction = textOrNull(node, "requiredAction");
            String rejectionReason = textOrNull(node, "rejectionReason");

            // Semantic-consistency check (beyond shape validation): a model can
            // return a well-formed but internally contradictory result. Rather
            // than trust it, we DOWNGRADE such results to ambiguous so the
            // workflow routes them to human review. This makes the LLM path as
            // conservative as the deterministic fallback, closing the gap where
            // "the safety story is strongest only when the LLM is unavailable".
            if (isInconsistent(classification, requiredAction, rejectionReason)) {
                ambiguous = true;
            }

            return Optional.of(new ClassificationResult(
                    classification,
                    confidence,
                    ambiguous,
                    referenceNumber,
                    deadline,
                    requiredAction,
                    rejectionReason,
                    rawJson,
                    "LLM"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * True when the classification contradicts the extracted fields. Note this is
     * consistency, not correctness: it cannot verify the model is <em>right</em>,
     * only that it did not return a self-contradictory answer.
     */
    static boolean isInconsistent(Classification classification, String requiredAction, String rejectionReason) {
        switch (classification) {
            case COMPLETED:
                // A completed deletion should not also demand an action from the user.
                return requiredAction != null;
            case REJECTED:
                // A rejection with no stated reason is suspect.
                return rejectionReason == null;
            case VERIFICATION_REQUIRED:
            case MORE_INFORMATION_REQUIRED:
                // These categories assert the company needs something; a missing
                // requiredAction contradicts that.
                return requiredAction == null;
            default:
                return false;
        }
    }

    /**
     * Deterministic keyword fallback. Produces a category with a modest confidence
     * when signals are clear, and flags ambiguity (low confidence) when signals are
     * absent or conflicting so the workflow routes it to human review.
     */
    ClassificationResult fallback(String body) {
        String t = body.toLowerCase(Locale.ROOT);

        boolean completed = t.contains("deleted") || t.contains("has been removed")
                || t.contains("erased") || t.contains("completed your request");
        boolean rejected = t.contains("cannot") || t.contains("unable to") || t.contains("denied")
                || t.contains("we will not") || t.contains("legal obligation to retain");
        boolean verify = t.contains("verify your identity") || t.contains("confirm your identity")
                || t.contains("proof of");
        boolean moreInfo = t.contains("provide your") || t.contains("need more information")
                || t.contains("additional information") || t.contains("account number");
        boolean received = t.contains("received your request") || t.contains("we have received")
                || t.contains("ticket") || t.contains("reference number");

        int signals = (completed ? 1 : 0) + (rejected ? 1 : 0) + (verify ? 1 : 0)
                + (moreInfo ? 1 : 0) + (received ? 1 : 0);

        String reference = findReference(body);

        // Conflicting signals -> ambiguous, low confidence.
        if (signals >= 2 || signals == 0) {
            return new ClassificationResult(Classification.UNKNOWN, 0.3, true,
                    reference, null, null, null, body, "FALLBACK");
        }
        if (completed) {
            return new ClassificationResult(Classification.COMPLETED, 0.7, false,
                    reference, null, null, null, body, "FALLBACK");
        }
        if (rejected) {
            return new ClassificationResult(Classification.REJECTED, 0.7, false,
                    reference, null, null, "See reply for stated reason.", body, "FALLBACK");
        }
        if (verify) {
            return new ClassificationResult(Classification.VERIFICATION_REQUIRED, 0.7, false,
                    reference, null, "Verify identity", null, body, "FALLBACK");
        }
        if (moreInfo) {
            return new ClassificationResult(Classification.MORE_INFORMATION_REQUIRED, 0.7, false,
                    reference, null, "Provide additional information", null, body, "FALLBACK");
        }
        // received
        return new ClassificationResult(Classification.RECEIVED, 0.65, false,
                reference, null, null, null, body, "FALLBACK");
    }

    private static String findReference(String text) {
        var matcher = java.util.regex.Pattern
                .compile("(?i)(?:ref(?:erence)?|ticket|case)\\s*(?:no\\.?|number|#|:)?\\s*([A-Za-z0-9-]{4,})")
                .matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }
}
