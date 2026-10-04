package com.privacypilot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code privacypilot.security.*}.
 *
 * <p>Two static bearer tokens model the two principals that call the API:
 * an <b>operator</b> (the dashboard/user acting on requests) and a <b>webhook</b>
 * (the mail provider posting inbound replies). This is intentionally the smallest
 * credible boundary for this project — not a full user-auth system — but it is
 * enforced by Spring Security, not by hand-rolled checks.
 */
@ConfigurationProperties(prefix = "privacypilot.security")
public class SecurityProperties {

    private String operatorToken = "";
    private String webhookToken = "";

    /** When no tokens are configured at all, the app runs in open "demo mode". */
    public boolean isEnforced() {
        return !operatorToken.isBlank() || !webhookToken.isBlank();
    }

    public String getOperatorToken() {
        return operatorToken;
    }

    public void setOperatorToken(String operatorToken) {
        this.operatorToken = operatorToken;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }
}
