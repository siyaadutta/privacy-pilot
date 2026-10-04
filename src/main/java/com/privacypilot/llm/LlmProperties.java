package com.privacypilot.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code privacypilot.llm.*} configuration.
 */
@ConfigurationProperties(prefix = "privacypilot.llm")
public class LlmProperties {

    /** gemini | openai */
    private String provider = "gemini";
    private String apiKey = "";
    private String model = "gemini-1.5-flash";
    private String baseUrl = "";
    private int timeoutSeconds = 30;

    public boolean isOfflineMode() {
        return apiKey == null || apiKey.isBlank();
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
