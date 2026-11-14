package com.llmeter.personal.quota.controller;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.ProviderQuota;
import com.llmeter.personal.quota.dto.QuotaHistoryResponse;
import com.llmeter.personal.quota.service.QuotaService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/api/quota")
public class QuotaController {
    private final QuotaService service;
    public QuotaController(final QuotaService service) { this.service = service; }
    @GetMapping
    public List<ProviderQuota> collect(@RequestParam(value = "provider", required = false) final ProviderType provider) {
        return service.collect(provider);
    }
    @GetMapping("/history")
    public QuotaHistoryResponse history(@RequestParam(value = "provider", required = false) final ProviderType provider,
                                       @RequestParam(value = "limit", defaultValue = "20") final int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History limit must be between 1 and 100");
        return service.history(provider, limit);
    }
}
