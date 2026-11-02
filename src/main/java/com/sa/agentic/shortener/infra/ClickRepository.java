package com.sa.agentic.shortener.infra;

import com.sa.agentic.shortener.domain.ClickEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClickRepository {

    private final JdbcClient jdbc;

    public ClickRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ClickEvent e) {
        jdbc.sql("INSERT INTO click_event (link_id, clicked_at, referrer, user_agent, visitor_hash) VALUES (?, ?, ?, ?, ?)")
                .params(e.linkId(), Timestamp.from(e.clickedAt()), e.referrer(), e.userAgent(), e.visitorHash())
                .update();
    }

    public long total(long linkId) {
        return jdbc.sql("SELECT COUNT(*) FROM click_event WHERE link_id = ?").param(linkId).query(Long.class).single();
    }

    public long uniqueVisitors(long linkId) {
        return jdbc.sql("SELECT COUNT(DISTINCT visitor_hash) FROM click_event WHERE link_id = ?")
                .param(linkId).query(Long.class).single();
    }

    /** Clicks per UTC day since {@code from}, oldest first. */
    public Map<String, Long> clicksPerDay(long linkId, Instant from) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT CAST(clicked_at AS DATE) AS d, COUNT(*) AS c
                FROM click_event WHERE link_id = ? AND clicked_at >= ?
                GROUP BY CAST(clicked_at AS DATE) ORDER BY d
                """)
                .params(linkId, Timestamp.from(from))
                .query((rs, n) -> out.put(rs.getDate("d").toString(), rs.getLong("c")))
                .list();
        return out;
    }

    public Map<String, Long> topReferrers(long linkId, int limit) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT COALESCE(referrer, '(direct)') AS r, COUNT(*) AS c
                FROM click_event WHERE link_id = ?
                GROUP BY COALESCE(referrer, '(direct)') ORDER BY c DESC LIMIT ?
                """)
                .params(linkId, limit)
                .query((rs, n) -> out.put(rs.getString("r"), rs.getLong("c")))
                .list();
        return out;
    }
}
