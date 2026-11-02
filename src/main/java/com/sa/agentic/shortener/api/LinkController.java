package com.sa.agentic.shortener.api;

import com.sa.agentic.shortener.ShortenerProperties;
import com.sa.agentic.shortener.domain.ShortLink;
import com.sa.agentic.shortener.service.AnalyticsService;
import com.sa.agentic.shortener.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links")
public class LinkController {

    public record CreateLinkRequest(@NotBlank @Size(max = 2048) String url) {
    }

    public record LinkResponse(String code, String shortUrl, String targetUrl, Instant createdAt) {
    }

    private final LinkService links;
    private final AnalyticsService analytics;
    private final ShortenerProperties props;

    public LinkController(LinkService links, AnalyticsService analytics, ShortenerProperties props) {
        this.links = links;
        this.analytics = analytics;
        this.props = props;
    }

    @PostMapping
    @Operation(summary = "Create a short link")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        ShortLink link = links.create(request.url());
        LinkResponse body = toResponse(link);
        return ResponseEntity.created(URI.create("/api/v1/links/" + link.code())).body(body);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get link metadata")
    public LinkResponse get(@PathVariable String code) {
        return toResponse(links.get(code));
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Deactivate a link (soft delete; click history is retained)")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        links.delete(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code}/stats")
    @Operation(summary = "Click analytics for a link")
    public AnalyticsService.Stats stats(@PathVariable String code) {
        ShortLink link = links.get(code);
        return analytics.stats(code, link.id());
    }

    private LinkResponse toResponse(ShortLink link) {
        return new LinkResponse(link.code(), props.baseUrl() + "/" + link.code(), link.targetUrl(), link.createdAt());
    }
}
