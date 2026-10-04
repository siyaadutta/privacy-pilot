package com.privacypilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link LlmClient} that talks to Gemini or OpenAI over HTTP.
 *
 * <p>Request bodies are constructed with Jackson ({@link ObjectMapper}) — never
 * by string concatenation. If the client is in offline mode (no API key) or any
 * error occurs (network, non-2xx, unparseable response), it returns
 * {@link Optional#empty()} so the calling service can fall back to its
 * deterministic path. It never throws to the caller.
 */
@Component
public class HttpLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmClient.class);

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public HttpLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                .build();
    }

    @Override
    public boolean isOffline() {
        return props.isOfflineMode();
    }

    @Override
    public Optional<String> complete(String systemInstruction, String userPrompt) {
        if (isOffline()) {
            log.debug("LLM offline (no API key configured); caller will use fallback.");
            return Optional.empty();
        }
        try {
            String provider = props.getProvider() == null ? "gemini" : props.getProvider().toLowerCase();
            if (provider.equals("openai")) {
                return callOpenAi(systemInstruction, userPrompt);
            }
            return callGemini(systemInstruction, userPrompt);
        } catch (Exception e) {
            log.warn("LLM call failed ({}); caller will use fallback.", e.toString());
            return Optional.empty();
        }
    }

    private Optional<String> callGemini(String systemInstruction, String userPrompt) throws Exception {
        String base = props.getBaseUrl() == null || props.getBaseUrl().isBlank()
                ? "https://generativelanguage.googleapis.com"
                : props.getBaseUrl();
        String url = base + "/v1beta/models/" + props.getModel() + ":generateContent?key=" + props.getApiKey();

        // Build body with Jackson, not string concatenation.
        ObjectNode root = mapper.createObjectNode();
        ObjectNode sysInstr = root.putObject("system_instruction");
        ArrayNode sysParts = sysInstr.putArray("parts");
        sysParts.addObject().put("text", systemInstruction);

        ArrayNode contents = root.putArray("contents");
        ObjectNode userContent = contents.addObject();
        userContent.put("role", "user");
        ArrayNode userParts = userContent.putArray("parts");
        userParts.addObject().put("text", userPrompt);

        ObjectNode genCfg = root.putObject("generationConfig");
        genCfg.put("temperature", 0.1);
        genCfg.put("responseMimeType", "application/json");

        HttpResponse<String> resp = post(url, mapper.writeValueAsString(root));
        if (resp.statusCode() / 100 != 2) {
            log.warn("Gemini returned status {}", resp.statusCode());
            return Optional.empty();
        }
        JsonNode node = mapper.readTree(resp.body());
        JsonNode text = node.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    private Optional<String> callOpenAi(String systemInstruction, String userPrompt) throws Exception {
        String base = props.getBaseUrl() == null || props.getBaseUrl().isBlank()
                ? "https://api.openai.com"
                : props.getBaseUrl();
        String url = base + "/v1/chat/completions";

        ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("temperature", 0.1);
        ObjectNode respFormat = root.putObject("response_format");
        respFormat.put("type", "json_object");
        ArrayNode messages = root.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemInstruction);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            log.warn("OpenAI returned status {}", resp.statusCode());
            return Optional.empty();
        }
        JsonNode node = mapper.readTree(resp.body());
        JsonNode content = node.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? Optional.of(content.asText()) : Optional.empty();
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
