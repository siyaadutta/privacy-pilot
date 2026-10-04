package com.privacypilot.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class that provides a real PostgreSQL for concurrency/locking integration
 * tests. Concurrency behavior (optimistic lock, SKIP LOCKED, unique-constraint
 * races) is Postgres-specific and cannot be exercised on H2.
 *
 * <p>By default it starts PostgreSQL via <b>Testcontainers</b> (the normal CI
 * path). If the {@code EXTERNAL_POSTGRES_URL} environment variable is set, it uses
 * that already-running database instead — this is an escape hatch for environments
 * where the Testcontainers docker client cannot initialize (e.g. Docker Desktop on
 * WSL, where the mediated socket is not recognized by docker-java). The test logic
 * is identical either way; only where the database comes from differs.
 *
 * <p>The outbox scheduled worker is disabled here so tests drive claim/deliver
 * deterministically rather than racing the background poller.
 */
@SpringBootTest(properties = {
        "privacypilot.outbox.worker-enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
public abstract class AbstractPostgresIT {

    private static final String EXTERNAL_URL = System.getenv("EXTERNAL_POSTGRES_URL");
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("privacypilot")
                    .withUsername("privacypilot")
                    .withPassword("privacypilot");
            POSTGRES.start();
        } else {
            POSTGRES = null; // using an externally-provided database
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("EXTERNAL_POSTGRES_USER", "privacypilot"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("EXTERNAL_POSTGRES_PASSWORD", "privacypilot"));
        }
    }
}
