package com.sa.agentic.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sa.agentic.shortener.domain.LinkException;
import com.sa.agentic.shortener.service.UrlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(
            new ShortenerProperties("http://x", 7, 100, 60, false,
                    new ShortenerProperties.RateLimit(true, 5, 60), new ShortenerProperties.Analytics(10, 1)));

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/a?b=c#d", "HTTPS://Example.com:8443/x"})
    void acceptsWebUrls(String url) {
        assertThat(validator.validate(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "not a url", "ftp://example.com", "javascript:alert(1)", "data:text/html,hi", "file:///etc/passwd",
            "https://user:pw@example.com", "https:///nohost",
            "http://localhost/admin", "http://127.0.0.1", "http://10.1.2.3", "http://172.20.0.1", "http://192.168.0.5",
            "http://169.254.169.254/latest/meta-data", "http://service.internal/x"})
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(LinkException.class);
    }

    @Test
    void rejectsOverlongUrls() {
        assertThatThrownBy(() -> validator.validate("https://example.com/" + "a".repeat(100)))
                .isInstanceOf(LinkException.class).hasMessageContaining("exceeds");
    }

    @Test
    void publicIpsAreNotMistakenForPrivate() {
        assertThat(validator.validate("http://172.32.0.1")).isEqualTo("http://172.32.0.1");
        assertThat(validator.validate("http://8.8.8.8")).isEqualTo("http://8.8.8.8");
    }
}
