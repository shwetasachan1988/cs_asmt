package com.sa.agentic.shortener.infra;

import com.sa.agentic.shortener.domain.ShortLink;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LinkRepository {

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long nextId() {
        return jdbc.sql("SELECT nextval('link_seq')").query(Long.class).single();
    }

    public void insert(ShortLink link) {
        jdbc.sql("INSERT INTO short_link (id, code, target_url, created_at, active) VALUES (?, ?, ?, ?, ?)")
                .params(link.id(), link.code(), link.targetUrl(), Timestamp.from(link.createdAt()), link.active())
                .update();
    }

    public Optional<ShortLink> findByCode(String code) {
        return jdbc.sql("SELECT id, code, target_url, created_at, active FROM short_link WHERE code = ?")
                .param(code)
                .query((rs, n) -> new ShortLink(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("target_url"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("active")))
                .optional();
    }

    /** Soft delete keeps click history for audit/analytics. Returns false when nothing was active. */
    public boolean deactivate(String code) {
        return jdbc.sql("UPDATE short_link SET active = FALSE WHERE code = ? AND active = TRUE")
                .param(code)
                .update() > 0;
    }
}
