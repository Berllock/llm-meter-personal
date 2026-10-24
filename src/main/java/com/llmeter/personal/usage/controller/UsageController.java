package com.llmeter.personal.usage.controller;

import com.llmeter.personal.usage.domain.dto.UsageSnapshot;
import com.llmeter.personal.usage.service.UsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(final UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public List<UsageSnapshot> getUsage() {
        return usageService.getUsage();
    }
}
