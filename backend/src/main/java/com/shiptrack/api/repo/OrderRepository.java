package com.shiptrack.api.repo;

import com.shiptrack.api.support.Utc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final String COLUMNS = "id, order_no, title, archived_at, created_at, updated_at";

    public static final RowMapper<OrderRecord> MAPPER = (rows, index) -> new OrderRecord(
            rows.getLong("id"),
            rows.getString("order_no"),
            rows.getString("title"),
            Utc.read(rows, "archived_at"),
            Utc.read(rows, "created_at"),
            Utc.read(rows, "updated_at"));

    /**
     * §1.3: stable order = highest_risk DESC, updated_at DESC, id DESC. The
     * composite risk rank is health issue (4) > NO_ETA (3) > DELAYED (2) >
     * EARLY (1) > NORMAL (0).
     */
    private static final String RISK_ORDER = """
            ORDER BY (SELECT CASE
                        WHEN COALESCE(SUM(c.tracking_health <> 'OK'), 0) > 0 THEN 4
                        WHEN COALESCE(SUM(c.eta_risk = 'NO_ETA'), 0) > 0 THEN 3
                        WHEN COALESCE(SUM(c.eta_risk = 'DELAYED'), 0) > 0 THEN 2
                        WHEN COALESCE(SUM(c.eta_risk = 'EARLY'), 0) > 0 THEN 1
                        ELSE 0 END
                     FROM containers c WHERE c.order_id = o.id) DESC,
                     o.updated_at DESC, o.id DESC
            """;

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String orderNo, String title) {
        Timestamp now = Utc.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO orders (order_no, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, orderNo);
            statement.setString(2, title);
            statement.setTimestamp(3, now);
            statement.setTimestamp(4, now);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("orders insert returned no id");
        }
        return key.longValue();
    }

    public Optional<OrderRecord> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public boolean existsByOrderNo(String orderNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_no = ?", Integer.class, orderNo);
        return count != null && count > 0;
    }

    public void update(long id, String orderNo, String title) {
        if (orderNo != null && title != null) {
            jdbc.update("UPDATE orders SET order_no = ?, title = ?, updated_at = ? WHERE id = ?",
                    orderNo, title, Utc.now(), id);
        } else if (orderNo != null) {
            jdbc.update("UPDATE orders SET order_no = ?, updated_at = ? WHERE id = ?",
                    orderNo, Utc.now(), id);
        } else if (title != null) {
            jdbc.update("UPDATE orders SET title = ?, updated_at = ? WHERE id = ?",
                    title, Utc.now(), id);
        }
    }

    public void archive(long id) {
        jdbc.update("UPDATE orders SET archived_at = ?, updated_at = ? WHERE id = ?",
                Utc.now(), Utc.now(), id);
        jdbc.update("UPDATE containers SET is_active = 0, updated_at = ? WHERE order_id = ?",
                Utc.now(), id);
    }

    public long count(OrderFilter filter) {
        Query query = buildWhere(filter);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM orders o " + query.where(),
                Long.class, query.args().toArray());
        return total == null ? 0 : total;
    }

    public List<Long> findIds(OrderFilter filter, int limit, int offset) {
        Query query = buildWhere(filter);
        List<Object> args = new ArrayList<>(query.args());
        args.add(limit);
        args.add(offset);
        String orderBy = "updated_desc".equals(filter.sort()) ? "ORDER BY o.updated_at DESC, o.id DESC"
                : RISK_ORDER;
        return jdbc.queryForList("SELECT o.id FROM orders o " + query.where() + " " + orderBy
                + " LIMIT ? OFFSET ?", Long.class, args.toArray());
    }

    private Query buildWhere(OrderFilter filter) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!filter.includeArchived()) {
            conditions.add("o.archived_at IS NULL");
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String pattern = "%" + escapeLike(filter.keyword().trim()) + "%";
            conditions.add("(o.order_no LIKE ? ESCAPE '\\\\' OR EXISTS (SELECT 1 FROM containers c "
                    + "WHERE c.order_id = o.id AND c.container_no LIKE ? ESCAPE '\\\\'))");
            args.add(pattern);
            args.add(pattern);
        }
        if (filter.transportStatus() != null && !filter.transportStatus().isBlank()) {
            conditions.add("EXISTS (SELECT 1 FROM containers c WHERE c.order_id = o.id "
                    + "AND c.transport_status = ?)");
            args.add(filter.transportStatus());
        }
        if (filter.etaRisk() != null && !filter.etaRisk().isBlank()) {
            conditions.add("EXISTS (SELECT 1 FROM containers c WHERE c.order_id = o.id "
                    + "AND c.eta_risk = ?)");
            args.add(filter.etaRisk());
        }
        if (filter.trackingHealth() != null && !filter.trackingHealth().isBlank()) {
            conditions.add("EXISTS (SELECT 1 FROM containers c WHERE c.order_id = o.id "
                    + "AND c.tracking_health = ?)");
            args.add(filter.trackingHealth());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return new Query(where, args);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public OffsetDateTime updatedAt(long orderId) {
        return jdbc.query("SELECT updated_at FROM orders WHERE id = ?",
                        (rows, index) -> Utc.read(rows, "updated_at"), orderId)
                .stream().findFirst().orElse(null);
    }

    private record Query(String where, List<Object> args) {
    }
}
