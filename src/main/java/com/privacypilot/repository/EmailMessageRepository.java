package com.privacypilot.repository;

import com.privacypilot.domain.EmailMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, Long> {
    List<EmailMessage> findByRequestIdOrderByTimestampAsc(Long requestId);

    /** Used to make inbound processing idempotent against duplicate delivery. */
    Optional<EmailMessage> findByRequestIdAndMessageId(Long requestId, String messageId);
}
