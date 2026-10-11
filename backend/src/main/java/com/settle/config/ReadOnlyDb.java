package com.settle.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Read-only SQL access for the agent's querySource tool. Tries to create a read-only role
 * (DB_READONLY_USER) if permissions allow; falls back to the main connection. Either way every
 * query runs in a READ ONLY transaction with a 5s statement timeout.
 */
@Component
public class ReadOnlyDb {
    private static final Logger log = LoggerFactory.getLogger(ReadOnlyDb.class);

    private final JdbcTemplate jdbc;

    public ReadOnlyDb(JdbcTemplate mainJdbc, DataSourceProperties dsProps, SettleProperties props) {
        this.jdbc = createReadOnly(mainJdbc, dsProps, props.readonly());
    }

    private static JdbcTemplate createReadOnly(JdbcTemplate mainJdbc, DataSourceProperties dsProps, SettleProperties.Readonly ro) {
        if (ro == null || ro.username() == null || ro.username().isBlank()) {
            log.info("No DB_READONLY_USER configured; querySource uses main connection in READ ONLY transactions");
            return mainJdbc;
        }
        String user = ro.username();
        if (user.equals(dsProps.getUsername())) {
            log.warn("DB_READONLY_USER equals DB_USER ('{}'); not a separate role - using main connection in READ ONLY transactions", user);
            return mainJdbc;
        }
        if (!user.matches("[a-z_][a-z0-9_]*")) {
            log.warn("DB_READONLY_USER '{}' is not a simple identifier; using main connection", user);
            return mainJdbc;
        }
        try {
            Integer exists = mainJdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, user);
            String pw = ro.password().replace("'", "''");
            if (exists == 0) {
                mainJdbc.execute("CREATE ROLE " + user + " LOGIN PASSWORD '" + pw + "'");
            }
            mainJdbc.execute("GRANT USAGE ON SCHEMA public TO " + user);
            mainJdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + user);
            DataSource ds = DataSourceBuilder.create()
                    .url(dsProps.getUrl()).username(user).password(ro.password()).build();
            JdbcTemplate roJdbc = new JdbcTemplate(ds);
            roJdbc.queryForObject("SELECT 1", Integer.class);
            log.info("querySource uses read-only role '{}'", user);
            return roJdbc;
        } catch (Exception e) {
            log.warn("Could not set up read-only role ({}); using main connection in READ ONLY transactions", e.getMessage());
            return mainJdbc;
        }
    }

    /** Runs a single SELECT read-only with a 5s timeout. Caller validates the SQL. */
    public List<Map<String, Object>> query(String sql) {
        return jdbc.execute((java.sql.Connection con) -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (var st = con.createStatement()) {
                st.execute("SET TRANSACTION READ ONLY");
                st.execute("SET LOCAL statement_timeout = '5s'");
                List<Map<String, Object>> rows = new java.util.ArrayList<>();
                try (var rs = st.executeQuery(sql)) {
                    var md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            Object v = rs.getObject(i);
                            row.put(md.getColumnLabel(i), v == null ? null : (v instanceof Number || v instanceof Boolean ? v : v.toString()));
                        }
                        rows.add(row);
                    }
                }
                return rows;
            } finally {
                con.rollback();
                con.setAutoCommit(auto);
            }
        });
    }
}
