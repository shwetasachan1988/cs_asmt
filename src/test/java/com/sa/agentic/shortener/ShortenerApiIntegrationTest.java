package com.sa.agentic.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.shortener.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortenerApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AnalyticsService analytics;

    private String create(String url, String remoteAddr) throws Exception {
        String body = mvc.perform(post("/api/v1/links").with(r -> {
                    r.setRemoteAddr(remoteAddr);
                    return r;
                }).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("code").asText();
    }

    @Test
    void createThenRedirectThenStats() throws Exception {
        String code = create("https://example.com/landing?x=1", "10.9.0.1");

        mvc.perform(get("/" + code).header("Referer", "https://news.example.org/").header("User-Agent", "JUnit"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/landing?x=1"));
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        assertThat(analytics.drain(3000)).isTrue();

        mvc.perform(get("/api/v1/links/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.uniqueVisitors").value(1))
                .andExpect(jsonPath("$.topReferrers['https://news.example.org/']").value(1))
                .andExpect(jsonPath("$.topReferrers['(direct)']").value(1));
    }

    @Test
    void getReturnsMetadata() throws Exception {
        String code = create("https://example.com/meta", "10.9.0.2");
        mvc.perform(get("/api/v1/links/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/meta"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/" + code));
    }

    @Test
    void deleteDeactivatesAndRedirectBecomesNotFound() throws Exception {
        String code = create("https://example.com/gone", "10.9.0.3");
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());

        mvc.perform(get("/" + code)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/links/" + code)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNotFound());
    }

    @Test
    void unknownCodeIsNotFoundProblemJson() throws Exception {
        mvc.perform(get("/Zz9Zz9Z"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        // second call is served from the negative cache and must behave identically
        mvc.perform(get("/Zz9Zz9Z")).andExpect(status().isNotFound());
    }

    @Test
    void invalidUrlsAreRejectedWith400() throws Exception {
        for (String bad : new String[]{"javascript:alert(1)", "http://localhost/x", "ftp://example.com"}) {
            mvc.perform(post("/api/v1/links").with(r -> {
                        r.setRemoteAddr("10.9.0.4");
                        return r;
                    }).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/links").with(r -> {
                    r.setRemoteAddr("10.9.0.4");
                    return r;
                }).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creationIsRateLimitedPerClientButRedirectsAreNot() throws Exception {
        String addr = "10.9.0.99";
        String code = create("https://example.com/rl", addr); // 1 of 5
        for (int i = 0; i < 4; i++) {
            create("https://example.com/rl" + i, addr);        // 5 of 5
        }
        mvc.perform(post("/api/v1/links").with(r -> {
                    r.setRemoteAddr(addr);
                    return r;
                }).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/over\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/" + code).with(r -> {
                r.setRemoteAddr(addr);
                return r;
            })).andExpect(status().isFound());
        }
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
