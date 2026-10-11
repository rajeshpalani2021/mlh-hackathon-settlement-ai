package com.settle.nimble;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Database access for unknown SWIFT codes that need research. */
@Repository
public class UnknownCodeRepository {

    private final JdbcTemplate db;

    public UnknownCodeRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void save(String code, UUID investigationId) {
        db.update("""
                INSERT INTO unknown_code (code, investigation_id, status)
                VALUES (?, ?, 'pending')
                ON CONFLICT (code) DO NOTHING
                """, code, investigationId);
    }

    public List<Map<String, Object>> findPending(int limit) {
        return db.queryForList("""
                SELECT id, code, investigation_id, status, nimble_sources, draft_page_path, created_at
                FROM unknown_code
                WHERE status = 'pending'
                ORDER BY created_at
                LIMIT ?
                """, limit);
    }

    public void markDrafted(long id, String draftPagePath, List<String> sources) {
        db.update("""
                UPDATE unknown_code
                SET status = 'drafted', draft_page_path = ?, nimble_sources = ?
                WHERE id = ?
                """, draftPagePath, sources.toArray(new String[0]), id);
    }

    public void markReviewed(long id) {
        db.update("UPDATE unknown_code SET status = 'reviewed' WHERE id = ?", id);
    }

    public List<Map<String, Object>> findAll() {
        return db.queryForList("""
                SELECT id, code, investigation_id, status, nimble_sources, draft_page_path, created_at
                FROM unknown_code
                ORDER BY created_at DESC
                """);
    }
}
