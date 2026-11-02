package com.sa.agentic.shortener.api;

import com.sa.agentic.shortener.domain.ResolvedLink;
import com.sa.agentic.shortener.service.AnalyticsService;
import com.sa.agentic.shortener.service.LinkService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Hidden
public class RedirectController {

    private final LinkService links;
    private final AnalyticsService analytics;

    public RedirectController(LinkService links, AnalyticsService analytics) {
        this.links = links;
        this.analytics = analytics;
    }

    /** 302 (not 301) so browsers do not cache the mapping and deletions take effect immediately. */
    @GetMapping("/{code:[0-9A-Za-z]{7}}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        ResolvedLink link = links.resolve(code);
        analytics.record(link.id(), request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.url())).build();
    }
}
