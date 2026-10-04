package com.privacypilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privacypilot.llm.LlmClient;
import com.privacypilot.workflow.Classification;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResponseClassifierServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final LlmClient offline = new LlmClient() {
        @Override public Optional<String> complete(String s, String u) { return Optional.empty(); }
        @Override public boolean isOffline() { return true; }
    };

    private final ResponseClassifierService service = new ResponseClassifierService(offline, mapper);

    @Test
    void fallbackClassifiesClearCompletion() {
        ClassificationResult r = service.classify(
                "Your data has been deleted from all our systems. Reference: REF-12345.");
        assertThat(r.classification()).isEqualTo(Classification.COMPLETED);
        assertThat(r.confidence()).isGreaterThanOrEqualTo(0.6);
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.referenceNumber()).isEqualTo("REF-12345");
    }

    @Test
    void fallbackClassifiesRejection() {
        ClassificationResult r = service.classify(
                "We are unable to complete this request due to a legal obligation to retain records.");
        assertThat(r.classification()).isEqualTo(Classification.REJECTED);
    }

    @Test
    void fallbackFlagsAmbiguousReplyForHumanReview() {
        // Vague reply with no clear signal -> ambiguous, low confidence.
        ClassificationResult r = service.classify(
                "Thanks for reaching out. We'll be in touch about your inquiry at some point.");
        assertThat(r.classification()).isEqualTo(Classification.UNKNOWN);
        assertThat(r.ambiguous()).isTrue();
        assertThat(r.confidence()).isLessThan(0.6);
    }

    @Test
    void fallbackFlagsConflictingSignalsAsAmbiguous() {
        // Contains both "deleted" and "unable to" -> conflicting -> ambiguous.
        ClassificationResult r = service.classify(
                "Some records were deleted, but we are unable to remove others.");
        assertThat(r.classification()).isEqualTo(Classification.UNKNOWN);
        assertThat(r.ambiguous()).isTrue();
    }

    @Test
    void parseValidJson() {
        String json = "{\"classification\": \"VERIFICATION_REQUIRED\", \"confidence\": 0.9, "
                + "\"ambiguous\": false, \"requiredAction\": \"Send ID\"}";
        Optional<ClassificationResult> r = service.parse(json);
        assertThat(r).isPresent();
        assertThat(r.get().classification()).isEqualTo(Classification.VERIFICATION_REQUIRED);
        assertThat(r.get().confidence()).isEqualTo(0.9);
        assertThat(r.get().requiredAction()).isEqualTo("Send ID");
        assertThat(r.get().source()).isEqualTo("LLM");
    }

    @Test
    void parseMissingConfidenceTreatedAsAmbiguous() {
        String json = "{\"classification\": \"COMPLETED\"}";
        Optional<ClassificationResult> r = service.parse(json);
        assertThat(r).isPresent();
        assertThat(r.get().confidence()).isEqualTo(0.0);
        assertThat(r.get().ambiguous()).isTrue();
    }

    @Test
    void parseInvalidCategoryReturnsEmpty() {
        String json = "{\"classification\": \"MAYBE\", \"confidence\": 0.9}";
        assertThat(service.parse(json)).isEmpty();
    }

    @Test
    void parseMalformedJsonReturnsEmpty() {
        assertThat(service.parse("not json at all")).isEmpty();
        assertThat(service.parse("{\"classification\":")).isEmpty();
    }

    @Test
    void confidenceIsClampedToUnitInterval() {
        String json = "{\"classification\": \"COMPLETED\", \"confidence\": 5.0, \"ambiguous\": false}";
        Optional<ClassificationResult> r = service.parse(json);
        assertThat(r).isPresent();
        assertThat(r.get().confidence()).isEqualTo(1.0);
    }

    // --- Semantic-consistency downgrade (beyond shape validation) ---

    @Test
    void completedWithRequiredActionIsDowngradedToAmbiguous() {
        // Internally contradictory: "done" but also "upload your passport".
        String json = "{\"classification\": \"COMPLETED\", \"confidence\": 0.99, "
                + "\"ambiguous\": false, \"requiredAction\": \"Upload your passport\"}";
        Optional<ClassificationResult> r = service.parse(json);
        assertThat(r).isPresent();
        assertThat(r.get().ambiguous()).isTrue();
    }

    @Test
    void rejectedWithoutReasonIsDowngradedToAmbiguous() {
        String json = "{\"classification\": \"REJECTED\", \"confidence\": 0.99, \"ambiguous\": false}";
        Optional<ClassificationResult> r = service.parse(json);
        assertThat(r).isPresent();
        assertThat(r.get().ambiguous()).isTrue();
    }

    @Test
    void verificationRequiredWithoutActionIsDowngradedToAmbiguous() {
        String json = "{\"classification\": \"VERIFICATION_REQUIRED\", \"confidence\": 0.95, \"ambiguous\": false}";
        Optional<ClassificationResult> r = service.parse(json);
        assertThat(r).isPresent();
        assertThat(r.get().ambiguous()).isTrue();
    }

    @Test
    void consistentResultIsNotDowngraded() {
        String json = "{\"classification\": \"VERIFICATION_REQUIRED\", \"confidence\": 0.95, "
                + "\"ambiguous\": false, \"requiredAction\": \"Send a photo ID\"}";
        Optional<ClassificationResult> r = service.parse(json);
        assertThat(r).isPresent();
        assertThat(r.get().ambiguous()).isFalse();
    }
}
