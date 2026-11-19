package com.ecommerce.monolith.order;

import com.ecommerce.monolith.order.entity.OrderItem;
import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderItemAmountSnapshotMigrationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void migrationPreservesLegacyLinesAndStoresExactSnapshots() {
        String schema = "order_item_amount_migration";
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target("7").load().migrate();
            jdbc.update("INSERT INTO " + schema + ".order_items (product_id, product_name, price, quantity) VALUES (1, 'existing', 1000, 2)");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            var legacy = jdbc.queryForMap("SELECT * FROM " + schema + ".order_items");
            assertThat(legacy.get("price")).isEqualTo(new BigDecimal("1000.00"));
            assertThat(legacy.get("quantity")).isEqualTo(2);
            assertThat(legacy.get("line_gross_amount")).isNull();
            assertThat(legacy.get("line_coupon_discount_amount")).isNull();
            assertThat(legacy.get("line_payment_amount")).isNull();

            OrderItem item = OrderItem.builder().price(new BigDecimal("1000.00")).quantity(2)
                    .lineGrossAmount(new BigDecimal("2000.00"))
                    .lineCouponDiscountAmount(new BigDecimal("333.00"))
                    .linePaymentAmount(new BigDecimal("1667.00")).build();
            jdbc.update("UPDATE " + schema + ".order_items SET line_gross_amount = ?, line_coupon_discount_amount = ?, line_payment_amount = ?",
                    item.getLineGrossAmount(), item.getLineCouponDiscountAmount(), item.getLinePaymentAmount());
            jdbc.update("UPDATE " + schema + ".order_items SET price = 9999, quantity = 3");
            var saved = jdbc.queryForMap("SELECT * FROM " + schema + ".order_items");
            assertThat(saved.get("line_gross_amount")).isEqualTo(item.getLineGrossAmount());
            assertThat(saved.get("line_coupon_discount_amount")).isEqualTo(item.getLineCouponDiscountAmount());
            assertThat(saved.get("line_payment_amount")).isEqualTo(item.getLinePaymentAmount());
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
