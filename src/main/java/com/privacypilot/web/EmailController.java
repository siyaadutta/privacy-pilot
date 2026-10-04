package com.privacypilot.web;

import com.privacypilot.domain.EmailMessage;
import com.privacypilot.service.DeletionRequestService;
import com.privacypilot.web.dto.ProcessEmailRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private final DeletionRequestService requestService;

    public EmailController(DeletionRequestService requestService) {
        this.requestService = requestService;
    }

    /**
     * Feed an inbound company reply into the pipeline. Returns a compact summary
     * of how it was classified and whether it was routed to human review.
     */
    @PostMapping("/process")
    public Map<String, Object> process(@Valid @RequestBody ProcessEmailRequest req) {
        EmailMessage msg = requestService.processInboundEmail(
                req.requestId(), req.subject(), req.body(), req.messageId());
        return Map.of(
                "emailId", msg.getId(),
                "requestId", req.requestId(),
                "classification", String.valueOf(msg.getClassification()),
                "confidence", msg.getClassificationConfidence() == null ? 0.0 : msg.getClassificationConfidence(),
                "resultingStatus", String.valueOf(msg.getRequest().getStatus()),
                "processedAt", Instant.now().toString());
    }
}
