package com.privacypilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A curated company that PrivacyPilot knows how to send deletion requests to.
 *
 * <p>The set of companies is seeded at startup; PrivacyPilot deliberately does
 * not attempt to support arbitrary companies discovered at runtime.
 */
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "privacy_policy_url")
    private String privacyPolicyUrl;

    /** e.g. EMAIL, WEB_FORM, POSTAL. Stored as free text for demo simplicity. */
    @Column(name = "deletion_method")
    private String deletionMethod;

    @Column(name = "privacy_email")
    private String privacyEmail;

    /** Comma-separated list of fields the company requires (e.g. "email,account_id"). */
    @Column(name = "required_information", length = 1000)
    private String requiredInformation;

    @Column(length = 2000)
    private String instructions;

    public Company() {
    }

    public Company(String name, String privacyPolicyUrl, String deletionMethod,
                   String privacyEmail, String requiredInformation, String instructions) {
        this.name = name;
        this.privacyPolicyUrl = privacyPolicyUrl;
        this.deletionMethod = deletionMethod;
        this.privacyEmail = privacyEmail;
        this.requiredInformation = requiredInformation;
        this.instructions = instructions;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrivacyPolicyUrl() {
        return privacyPolicyUrl;
    }

    public void setPrivacyPolicyUrl(String privacyPolicyUrl) {
        this.privacyPolicyUrl = privacyPolicyUrl;
    }

    public String getDeletionMethod() {
        return deletionMethod;
    }

    public void setDeletionMethod(String deletionMethod) {
        this.deletionMethod = deletionMethod;
    }

    public String getPrivacyEmail() {
        return privacyEmail;
    }

    public void setPrivacyEmail(String privacyEmail) {
        this.privacyEmail = privacyEmail;
    }

    public String getRequiredInformation() {
        return requiredInformation;
    }

    public void setRequiredInformation(String requiredInformation) {
        this.requiredInformation = requiredInformation;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }
}
