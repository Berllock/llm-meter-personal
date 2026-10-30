package com.llmeter.personal.desktop.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class DesktopTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-LLM-Meter-Token";
    private final byte[] token;

    public DesktopTokenFilter(final String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("A desktop request token is required");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain
    ) throws ServletException, IOException {
        final var supplied = request.getHeader(HEADER);
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
