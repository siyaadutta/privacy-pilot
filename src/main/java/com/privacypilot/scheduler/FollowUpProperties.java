package com.privacypilot.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code privacypilot.followup.*}.
 */
@ConfigurationProperties(prefix = "privacypilot.followup")
public class FollowUpProperties {

    /** How long to wait for a reply before a request is eligible for follow-up. */
    private int waitWindowHours = 168;

    /** Cron used by the scheduler that scans for due follow-ups. */
    private String scanCron = "0 0 * * * *";

    /** From-address used on follow-up emails. */
    private String senderEmail = "privacy@privacypilot.example";

    public int getWaitWindowHours() {
        return waitWindowHours;
    }

    public void setWaitWindowHours(int waitWindowHours) {
        this.waitWindowHours = waitWindowHours;
    }

    public String getScanCron() {
        return scanCron;
    }

    public void setScanCron(String scanCron) {
        this.scanCron = scanCron;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }
}
