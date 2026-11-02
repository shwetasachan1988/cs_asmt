package com.sa.agentic.orchestrator.api;

import com.sa.agentic.orchestrator.OrchestratorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Optional guard for the control plane: when {@code orchestrator.api-key} is set, every state-changing call must
 * carry it in {@code X-API-Key}. Reads stay open so the dashboard can render without credentials in the demo.
 * (Production would sit behind real authN/authZ; approver identity here is asserted by the caller.)
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public ApiKeyFilter(OrchestratorProperties props) {
        this.expected = props.apiKey() == null || props.apiKey().isBlank() ? null
                : props.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expected == null || !request.getRequestURI().startsWith("/api/orchestrator")
                || HttpMethod.GET.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Key");
        if (supplied != null && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"valid X-API-Key required\"}");
    }
}
