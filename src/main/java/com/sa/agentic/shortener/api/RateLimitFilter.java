package com.sa.agentic.shortener.api;

import com.sa.agentic.shortener.ShortenerProperties;
import com.sa.agentic.shortener.infra.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Limits link creation per client address. Redirects are intentionally unthrottled (read-heavy hot path). */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ShortenerProperties props;

    public RateLimitFilter(RateLimiter limiter, ShortenerProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.rateLimit().enabled()
                || !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().equals("/api/v1/links");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (limiter.tryAcquire(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(props.rateLimit().windowSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"rate limit exceeded, retry later\"}");
    }
}
