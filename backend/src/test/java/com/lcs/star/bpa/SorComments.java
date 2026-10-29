package com.lcs.star.bpa;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** The S17 check: every table in schema bpa carries an SOR comment. */
final class SorComments {

    private SorComments() {
    }

    /** Base tables in schema bpa without a table comment; flyway_schema_history is excluded by name. */
    static List<String> tablesWithoutComment(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'bpa' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history' "
                        + "AND obj_description(c.oid, 'pg_class') IS NULL ORDER BY c.relname",
                String.class);
    }
}
