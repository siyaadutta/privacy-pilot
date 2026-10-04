package com.privacypilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.privacypilot.llm.ClassifierProperties;
import com.privacypilot.llm.LlmProperties;
import com.privacypilot.scheduler.FollowUpProperties;

/**
 * PrivacyPilot entry point.
 *
 * <p>PrivacyPilot automates the workflow of requesting deletion of a user's
 * personal data from a curated list of companies. It does NOT delete data from
 * arbitrary third-party systems; it automates discovery of a company's deletion
 * process, generation of a personalized request, submission, response
 * interpretation, and follow-up tracking.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({LlmProperties.class, ClassifierProperties.class, FollowUpProperties.class})
public class PrivacyPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrivacyPilotApplication.class, args);
    }
}
