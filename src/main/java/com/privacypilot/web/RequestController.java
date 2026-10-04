package com.privacypilot.web;

import com.privacypilot.service.DeletionRequestService;
import com.privacypilot.web.dto.CreateRequestRequest;
import com.privacypilot.web.dto.RequestView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final DeletionRequestService requestService;

    public RequestController(DeletionRequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    public ResponseEntity<RequestView> create(@Valid @RequestBody CreateRequestRequest req) {
        RequestView view = RequestView.from(requestService.create(req.userId(), req.companyId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    public List<RequestView> list() {
        return requestService.list().stream().map(RequestView::from).toList();
    }

    @GetMapping("/{id}")
    public RequestView get(@PathVariable Long id) {
        return RequestView.from(requestService.get(id));
    }

    @PostMapping("/{id}/approve")
    public RequestView approve(@PathVariable Long id) {
        return RequestView.from(requestService.approve(id));
    }

    @PostMapping("/{id}/send")
    public RequestView send(@PathVariable Long id) {
        return RequestView.from(requestService.send(id));
    }

    @PostMapping("/{id}/follow-up")
    public RequestView followUp(@PathVariable Long id) {
        return RequestView.from(requestService.followUp(id));
    }
}
