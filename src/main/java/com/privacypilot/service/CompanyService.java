package com.privacypilot.service;

import com.privacypilot.ai.PolicyExtractionResult;
import com.privacypilot.ai.PolicyExtractorService;
import com.privacypilot.domain.Company;
import com.privacypilot.repository.CompanyRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CompanyService {

    private final CompanyRepository companies;
    private final PolicyExtractorService policyExtractor;

    public CompanyService(CompanyRepository companies, PolicyExtractorService policyExtractor) {
        this.companies = companies;
        this.policyExtractor = policyExtractor;
    }

    public List<Company> list() {
        return companies.findAll();
    }

    /**
     * Analyze a privacy policy into structured deletion info (AI function #1).
     */
    public PolicyExtractionResult analyze(String policyText) {
        return policyExtractor.extract(policyText);
    }
}
