package com.llmeter.personal.usage.controller;

import com.llmeter.personal.usage.domain.UsagePeriod;
import com.llmeter.personal.usage.dto.DailyUsage;
import com.llmeter.personal.usage.dto.DailyUsageCollection;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import com.llmeter.personal.usage.service.UsageService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
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

    @GetMapping("/daily")
    public List<DailyUsage> getDailyUsage(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(value = "zone", defaultValue = "UTC") final String zone
    ) {
        return usageService.getDailyUsage(period(from, to, zone));
    }

    @GetMapping("/daily/collection")
    public DailyUsageCollection collectDailyUsage(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(value = "zone", defaultValue = "UTC") final String zone
    ) {
        return usageService.collectDailyUsage(period(from, to, zone));
    }

    private UsagePeriod period(final LocalDate from, final LocalDate to, final String zone) {
        try {
            return new UsagePeriod(from, to, ZoneId.of(zone));
        } catch (final DateTimeException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid usage period or time zone");
        }
    }
}
