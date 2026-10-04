package com.privacypilot.service;

import com.privacypilot.domain.Company;
import com.privacypilot.repository.CompanyRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a curated set of companies at startup (idempotent — only inserts a
 * company if its name is not already present). PrivacyPilot deliberately supports
 * only this curated set rather than arbitrary companies.
 */
@Component
public class CompanySeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanySeeder.class);

    private final CompanyRepository companies;

    public CompanySeeder(CompanyRepository companies) {
        this.companies = companies;
    }

    @Override
    public void run(String... args) {
        List<Company> seed = List.of(
                new Company("Acme Retail", "https://acme.example/privacy", "EMAIL",
                        "privacy@acme.example", "full name,account email", "Email the privacy team with your account email."),
                new Company("Globex", "https://globex.example/privacy", "WEB_FORM",
                        "dpo@globex.example", "full name,account email,order id", "Submit the online data request form."),
                new Company("Initech", "https://initech.example/privacy", "EMAIL",
                        "privacy@initech.example", "full name,account email", "Email privacy@ with subject 'Data Deletion'."),
                new Company("Umbrella Media", "https://umbrella.example/privacy", "EMAIL",
                        "gdpr@umbrella.example", "full name,account email,date of birth", "Include DOB for identity verification."),
                new Company("Soylent Foods", "https://soylent.example/privacy", "WEB_FORM",
                        "privacy@soylent.example", "account email", "Use the privacy portal."),
                new Company("Hooli", "https://hooli.example/privacy", "EMAIL",
                        "privacy@hooli.example", "full name,account email,username", "Provide username used at signup."),
                new Company("Stark Solutions", "https://stark.example/privacy", "EMAIL",
                        "dataprivacy@stark.example", "full name,account email", "Written confirmation provided within 30 days."),
                new Company("Wayne Digital", "https://wayne.example/privacy", "POSTAL",
                        "privacy@wayne.example", "full name,postal address", "Postal verification for sensitive accounts."),
                new Company("Wonka Industries", "https://wonka.example/privacy", "WEB_FORM",
                        "privacy@wonka.example", "account email", "Golden-ticket holders use the portal."),
                new Company("Cyberdyne", "https://cyberdyne.example/privacy", "EMAIL",
                        "privacy@cyberdyne.example", "full name,account email,device id", "Include device ID if applicable."),
                new Company("Vandelay Industries", "https://vandelay.example/privacy", "EMAIL",
                        "privacy@vandelay.example", "full name,account email", "Latex-grade confidentiality assured."),
                new Company("Pied Piper", "https://piedpiper.example/privacy", "WEB_FORM",
                        "privacy@piedpiper.example", "account email", "Use in-app data request."),
                new Company("Massive Dynamic", "https://massivedynamic.example/privacy", "EMAIL",
                        "privacy@massivedynamic.example", "full name,account email", "Response within 45 days."),
                new Company("Aperture Labs", "https://aperture.example/privacy", "EMAIL",
                        "privacy@aperture.example", "full name,account email,test subject id", "Provide subject ID if enrolled."),
                new Company("Nakatomi Trading", "https://nakatomi.example/privacy", "WEB_FORM",
                        "privacy@nakatomi.example", "account email", "Submit through the corporate portal.")
        );

        int inserted = 0;
        for (Company c : seed) {
            if (!companies.existsByName(c.getName())) {
                companies.save(c);
                inserted++;
            }
        }
        log.info("Company seeding complete: {} inserted, {} total.", inserted, companies.count());
    }
}
