package com.ecommerce.monolith.order;

import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

class OrderItemStatusMigrationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void migrationBackfillsExistingLinesAndDefaultsNewLines() {
        String schema = "order_item_status_migration";
        Flyway before = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("6").load();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            before.migrate();
            jdbc.update("INSERT INTO " + schema + ".order_items (product_id, product_name, price, quantity) VALUES (1, 'existing', 1000, 1)");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".order_items", String.class))
                    .isEqualTo("ORDERED");
            jdbc.update("INSERT INTO " + schema + ".order_items (product_id, product_name, price, quantity) VALUES (2, 'new', 2000, 1)");
            assertThat(jdbc.queryForList("SELECT status FROM " + schema + ".order_items", String.class))
                    .containsExactly("ORDERED", "ORDERED");
            assertThatThrownBy(() -> jdbc.update("UPDATE " + schema + ".order_items SET status = NULL"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
