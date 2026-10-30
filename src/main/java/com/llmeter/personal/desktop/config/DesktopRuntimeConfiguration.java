package com.llmeter.personal.desktop.config;

import com.llmeter.personal.desktop.filter.DesktopTokenFilter;
import com.llmeter.personal.desktop.service.DesktopReadinessWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@ConditionalOnProperty(prefix = "llm-meter.desktop", name = "ready-file")
public class DesktopRuntimeConfiguration {

    @Bean
    public ApplicationListener<ApplicationReadyEvent> desktopReadiness(
            @Value("${llm-meter.desktop.ready-file}") final String readyFile,
            @Value("${llm-meter.desktop.instance-id}") final String instanceId
    ) {
        return (final var event) -> {
            if (!(event.getApplicationContext() instanceof WebServerApplicationContext context)) {
                throw new IllegalStateException("Desktop mode requires an HTTP server");
            }
            new DesktopReadinessWriter().publish(Path.of(readyFile), context.getWebServer().getPort(), instanceId);
        };
    }

    @Bean
    public FilterRegistrationBean<DesktopTokenFilter> desktopTokenFilter(
            @Value("${LLM_METER_DESKTOP_TOKEN}") final String token
    ) {
        final var registration = new FilterRegistrationBean<>(new DesktopTokenFilter(token));
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
