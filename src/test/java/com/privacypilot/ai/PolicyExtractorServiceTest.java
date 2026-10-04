package com.privacypilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privacypilot.llm.LlmClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyExtractorServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** An LLM client that is always offline (returns empty), forcing the fallback path. */
    private final LlmClient offline = new LlmClient() {
        @Override public Optional<String> complete(String s, String u) { return Optional.empty(); }
        @Override public boolean isOffline() { return true; }
    };

    private final PolicyExtractorService service = new PolicyExtractorService(offline, mapper);

    @Test
    void policyWithNoDeletionLanguageReturnsNotSupported() {
        String policy = "We collect your data to provide services and improve our products. "
                + "We may share aggregated statistics with partners. We use cookies for analytics.";
        PolicyExtractionResult result = service.extract(policy);
        assertThat(result.deletionSupported()).isFalse();
        assertThat(result.deletionMethod()).isNull();
        assertThat(result.contact()).isNull();
    }

    @Test
    void policyWithDeletionLanguageReturnsSupported() {
        String policy = "You have the right to request deletion of your personal data. "
                + "Email privacy@example.com to erase your account.";
        PolicyExtractionResult result = service.extract(policy);
        assertThat(result.deletionSupported()).isTrue();
        assertThat(result.contact()).isEqualTo("privacy@example.com");
        assertThat(result.deletionMethod()).isEqualTo("EMAIL");
    }

    @Test
    void policyThatExplicitlyRefusesDeletionIsNotSupported() {
        // Mentions "delete" but denies it — must not be reported as supported.
        String policy = "We do not delete personal data upon request; records are retained indefinitely.";
        PolicyExtractionResult result = service.extract(policy);
        assertThat(result.deletionSupported()).isFalse();
    }

    @Test
    void fallbackDoesNotInventAMethodWhenNoneEvidenced() {
        // Deletion is offered, but the text gives no email and no form/portal wording.
        String policy = "You may request deletion of your personal data at any time.";
        PolicyExtractionResult result = service.extract(policy);
        assertThat(result.deletionSupported()).isTrue();
        assertThat(result.contact()).isNull();
        assertThat(result.deletionMethod()).isNull(); // not defaulted to WEB_FORM
    }

    @Test
    void fallbackInfersWebFormOnlyWhenTextEvidencesIt() {
        String policy = "To request deletion, submit the online data-removal form in your account settings.";
        PolicyExtractionResult result = service.extract(policy);
        assertThat(result.deletionSupported()).isTrue();
        assertThat(result.deletionMethod()).isEqualTo("WEB_FORM");
    }

    @Test
    void parseValidJsonSupported() {
        String json = "{\"deletionSupported\": true, \"deletionMethod\": \"EMAIL\", "
                + "\"contact\": \"dpo@x.com\", \"requiredInformation\": [\"email\"], "
                + "\"responseDeadline\": \"30 days\"}";
        Optional<PolicyExtractionResult> parsed = service.parse(json);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().deletionSupported()).isTrue();
        assertThat(parsed.get().requiredInformation()).containsExactly("email");
        assertThat(parsed.get().source()).isEqualTo("LLM");
    }

    @Test
    void parseValidJsonNotSupported() {
        String json = "{\"deletionSupported\": false}";
        Optional<PolicyExtractionResult> parsed = service.parse(json);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().deletionSupported()).isFalse();
    }

    @Test
    void parseMalformedJsonReturnsEmpty() {
        assertThat(service.parse("this is not json")).isEmpty();
        assertThat(service.parse("{\"deletionSupported\":")).isEmpty();
        // missing required boolean field
        assertThat(service.parse("{\"deletionMethod\": \"EMAIL\"}")).isEmpty();
        // wrong type for the required field
        assertThat(service.parse("{\"deletionSupported\": \"yes\"}")).isEmpty();
    }

    @Test
    void parseHandlesJsonWrappedInProse() {
        String wrapped = "Here is the result: {\"deletionSupported\": false} -- hope that helps!";
        Optional<PolicyExtractionResult> parsed = service.parse(wrapped);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().deletionSupported()).isFalse();
    }

    @Test
    void extractJsonObjectRespectsBracesInsideStrings() {
        // A brace inside a string value must not end the object early.
        String raw = "{\"deletionSupported\": true, \"contact\": \"reply to {ops} team\", "
                + "\"deletionMethod\": \"EMAIL\", \"requiredInformation\": [], \"responseDeadline\": null}";
        assertThat(PolicyExtractorService.extractJsonObject("noise " + raw + " trailing"))
                .isEqualTo(raw);
    }

    @Test
    void extractJsonObjectReturnsFirstCompleteObjectIgnoringTrailing() {
        String first = "{\"deletionSupported\": false}";
        String combined = first + " {\"deletionSupported\": true}";
        assertThat(PolicyExtractorService.extractJsonObject(combined)).isEqualTo(first);
    }

    @Test
    void extractJsonObjectLeavesUnbalancedInputForParserToReject() {
        String unbalanced = "{\"deletionSupported\": true, \"contact\": \"x\"";
        // Returned unchanged; a subsequent parse fails and the caller falls back.
        assertThat(service.parse(unbalanced)).isEmpty();
    }
}
