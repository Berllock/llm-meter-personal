package com.llmeter.personal.quota.controller;

import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.quota.dto.ProviderQuota;
import com.llmeter.personal.quota.service.QuotaService;
import org.springframework.web.bind.annotation.*;
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
}
