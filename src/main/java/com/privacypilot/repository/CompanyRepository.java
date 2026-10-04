package com.privacypilot.repository;

import com.privacypilot.domain.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByName(String name);

    boolean existsByName(String name);
}
