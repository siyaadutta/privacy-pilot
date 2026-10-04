package com.privacypilot.web;

import com.privacypilot.ai.PolicyExtractionResult;
import com.privacypilot.domain.Company;
import com.privacypilot.service.CompanyService;
import com.privacypilot.web.dto.AnalyzePolicyRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    public List<Company> list() {
        return companyService.list();
    }

    @PostMapping("/analyze")
    public PolicyExtractionResult analyze(@Valid @RequestBody AnalyzePolicyRequest req) {
        return companyService.analyze(req.policyText());
    }
}
