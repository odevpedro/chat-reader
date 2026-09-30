package com.example.chatreader.search.infrastructure;

import com.example.chatreader.search.domain.SearchHit;
import com.example.chatreader.search.domain.SearchRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * Busca full-text com as colunas geradas + GIN da migracao V5 (secao 13).
 * Usa 'simple' (sem stemming) para funcionar em portugues e ingles.
 *
 * <p>Cobre tres origens: conteudo das mensagens, titulo do chat e tag. Quando o
 * casamento e por conteudo, cada mensagem correspondente vira um hit (com
 * snippet). Quando o casamento e apenas por titulo/tag, devolvemos <b>um</b> hit
 * por chat (a primeira mensagem como referencia), evitando repetir o mesmo chat
 * para cada mensagem.
 */
@Repository
public class SearchQueryRepository implements SearchRepository {

    private static final String TSQ = "WITH tsq AS (SELECT plainto_tsquery('simple', :q) AS query)";

    private static final String CONTENT_MATCH = """
            SELECT c.id AS chat_id, c.title AS title, m.id AS message_id, m.role AS role,
                   ts_headline('simple', m.content, tsq.query,
                       'StartSel=<mark>, StopSel=</mark>, MaxWords=40, MinWords=8, ShortWord=2') AS snippet,
                   m.created_at AS created_at,
                   0 AS bucket,
                   ts_rank(m.content_tsv, tsq.query) AS rank
            FROM messages m
            JOIN chats c ON c.id = m.chat_id
            CROSS JOIN tsq
            WHERE c.deleted = false AND m.content_tsv @@ tsq.query
            """;

    private static final String TITLE_OR_TAG_MATCH = """
            SELECT c.id, c.title, m.id, m.role,
                   ts_headline('simple', c.title, tsq.query,
                       'StartSel=<mark>, StopSel=</mark>, MaxWords=40, MinWords=8, ShortWord=2'),
                   m.created_at,
                   1,
                   0
            FROM chats c
            CROSS JOIN tsq
            JOIN LATERAL (
                SELECT m2.id, m2.role, m2.created_at
                FROM messages m2
                WHERE m2.chat_id = c.id
                ORDER BY m2.sequence
                LIMIT 1
            ) m ON true
            WHERE c.deleted = false
              AND (c.title_tsv @@ tsq.query
                   OR EXISTS (
                       SELECT 1 FROM chat_tags ct
                       JOIN tags t ON t.id = ct.tag_id
                       WHERE ct.chat_id = c.id AND t.normalized_name = lower(btrim(:q))
                   ))
              AND NOT EXISTS (
                  SELECT 1 FROM messages mm
                  WHERE mm.chat_id = c.id AND mm.content_tsv @@ tsq.query
              )
            """;

    private static final String SEARCH_SQL = TSQ + """
            SELECT chat_id, title, message_id, role, snippet, created_at
            FROM (
            %s
            UNION ALL
            %s
            ) hits
            ORDER BY bucket ASC, rank DESC, created_at DESC NULLS LAST, message_id ASC
            LIMIT :limit OFFSET :offset
            """.formatted(CONTENT_MATCH, TITLE_OR_TAG_MATCH);

    private static final String COUNT_SQL = TSQ + """
            SELECT
              (SELECT count(*)
                 FROM messages m
                 JOIN chats c ON c.id = m.chat_id
                 CROSS JOIN tsq
                WHERE c.deleted = false AND m.content_tsv @@ tsq.query)
              +
              (SELECT count(*)
                 FROM chats c
                 CROSS JOIN tsq
                WHERE c.deleted = false
                  AND (c.title_tsv @@ tsq.query
                       OR EXISTS (
                           SELECT 1 FROM chat_tags ct
                           JOIN tags t ON t.id = ct.tag_id
                           WHERE ct.chat_id = c.id AND t.normalized_name = lower(btrim(:q))
                       ))
                  AND NOT EXISTS (
                      SELECT 1 FROM messages mm
                      WHERE mm.chat_id = c.id AND mm.content_tsv @@ tsq.query
                  ))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SearchQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHit> search(String query, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("q", query)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        return jdbc.query(SEARCH_SQL, params, new HitRowMapper());
    }

    @Override
    @Transactional(readOnly = true)
    public long count(String query) {
        var params = new MapSqlParameterSource().addValue("q", query);
        var total = jdbc.queryForObject(COUNT_SQL, params, Long.class);
        return total == null ? 0 : total;
    }

    private static final class HitRowMapper implements RowMapper<SearchHit> {
        @Override
        public SearchHit mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new SearchHit(
                    rs.getString("chat_id"),
                    rs.getString("title"),
                    rs.getString("message_id"),
                    rs.getString("role"),
                    rs.getString("snippet"),
                    createdAt == null ? null : createdAt.toInstant());
        }
    }
}
