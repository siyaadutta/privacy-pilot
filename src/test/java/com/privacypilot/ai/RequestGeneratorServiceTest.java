package com.privacypilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privacypilot.domain.Company;
import com.privacypilot.domain.User;
import com.privacypilot.llm.LlmClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestGeneratorServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final LlmClient offline = new LlmClient() {
        @Override public Optional<String> complete(String s, String u) { return Optional.empty(); }
        @Override public boolean isOffline() { return true; }
    };

    private final RequestGeneratorService service = new RequestGeneratorService(offline, mapper);

    private final User user = new User("Jane Doe", "jane@example.com", null, null);
    private final Company company = new Company("Acme", "https://acme/privacy", "EMAIL",
            "privacy@acme.example", "full name,account email", "Email us.");

    @Test
    void offlineFallsBackToTemplateContainingUserAndCompany() {
        String text = service.generate(user, company);
        assertThat(text).contains("Jane Doe");
        assertThat(text).contains("jane@example.com");
        assertThat(text).contains("Acme");
        assertThat(text).containsIgnoringCase("deletion");
    }

    @Test
    void parseValidJsonReturnsText() {
        Optional<String> text = service.parse("{\"requestText\": \"Please delete my data.\"}");
        assertThat(text).contains("Please delete my data.");
    }

    @Test
    void parseMalformedJsonReturnsEmpty() {
        assertThat(service.parse("not json")).isEmpty();
        assertThat(service.parse("{\"requestText\": \"\"}")).isEmpty();
        assertThat(service.parse("{\"other\": \"x\"}")).isEmpty();
    }
}
