package com.llmeter.personal.desktop.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopTokenFilterTest {

    private static final String TOKEN = "synthetic-desktop-token-with-32-characters";

    // Verifies missing and incorrect desktop credentials stop collection before the request reaches a controller.
    @ParameterizedTest
    @ValueSource(strings = {"", "wrong-token"})
    void shouldRejectUnauthorizedRequests(final String supplied) throws Exception {
        final var request = new MockHttpServletRequest("GET", "/api/usage");
        if (!supplied.isEmpty()) request.addHeader(DesktopTokenFilter.HEADER, supplied);
        final var response = new MockHttpServletResponse();
        final var chain = new MockFilterChain();
        new DesktopTokenFilter(TOKEN).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).isNull();
    }

    // Verifies the owned desktop service accepts the main process's launch-specific credential.
    @Test
    void shouldAllowAuthorizedRequests() throws Exception {
        final var request = new MockHttpServletRequest("GET", "/api/usage");
        request.addHeader(DesktopTokenFilter.HEADER, TOKEN);
        final var chain = new MockFilterChain();
        new DesktopTokenFilter(TOKEN).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
