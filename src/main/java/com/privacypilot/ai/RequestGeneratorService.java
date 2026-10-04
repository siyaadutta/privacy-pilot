package com.privacypilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.privacypilot.domain.Company;
import com.privacypilot.domain.User;
import com.privacypilot.llm.LlmClient;

/**
 * AI function #2: generate a personalized, editable deletion request.
 *
 * <p>The generated text is returned to the user for review/edit/approval; it is
 * never auto-sent. The LLM is asked to return JSON {@code {"requestText": ...}};
 * if that fails validation or the LLM is offline, a deterministic template is
 * used so the workflow always has a usable draft.
 */
@Service
public class RequestGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(RequestGeneratorService.class);

    private static final String SYSTEM = """
            You write concise, polite personal-data deletion requests (GDPR/CCPA
            style). Respond ONLY with JSON: {"requestText": string}. The text must
            be ready for a human to review and edit before sending. Do not fabricate
            account identifiers the user did not provide.""";

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public RequestGeneratorService(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public String generate(User user, Company company) {
        String prompt = buildPrompt(user, company);
        Optional<String> raw = llm.complete(SYSTEM, prompt);
        if (raw.isPresent()) {
            Optional<String> text = parse(raw.get());
            if (text.isPresent()) {
                return text.get();
            }
            log.warn("Request generator: LLM output failed validation; using template fallback.");
        }
        return template(user, company);
    }

    Optional<String> parse(String rawJson) {
        try {
            JsonNode node = mapper.readTree(PolicyExtractorService.extractJsonObject(rawJson));
            JsonNode text = node.get("requestText");
            if (text != null && text.isTextual() && !text.asText().isBlank()) {
                return Optional.of(text.asText());
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String buildPrompt(User user, Company company) {
        return "User: " + safe(user.getName())
                + " <" + safe(user.getEmail()) + ">\n"
                + "Company: " + safe(company.getName()) + "\n"
                + "Company deletion method: " + safe(company.getDeletionMethod()) + "\n"
                + "Company required information: " + safe(company.getRequiredInformation()) + "\n"
                + "Write a deletion request for this user to this company.";
    }

    String template(User user, Company company) {
        return "Subject: Request for Deletion of Personal Data\n\n"
                + "To " + safe(company.getName()) + " Privacy Team,\n\n"
                + "I, " + safe(user.getName()) + ", am exercising my right to request the "
                + "deletion of all personal data you hold about me, associated with the "
                + "email address " + safe(user.getEmail()) + ".\n\n"
                + "Please confirm receipt of this request and let me know if you require any "
                + "additional information to verify my identity. I look forward to written "
                + "confirmation once the deletion is complete.\n\n"
                + "Regards,\n" + safe(user.getName());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
