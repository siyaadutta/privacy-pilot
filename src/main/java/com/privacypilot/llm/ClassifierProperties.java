package com.privacypilot.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code privacypilot.classifier.*}.
 */
@ConfigurationProperties(prefix = "privacypilot.classifier")
public class ClassifierProperties {

    /**
     * Minimum confidence required to accept a classification. Below this, the
     * reply is routed to human review (USER_ACTION_REQUIRED) rather than being
     * forced into a category. This is the precision/recall knob.
     */
    private double confidenceThreshold = 0.6;

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }
}
