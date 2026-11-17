ALTER TABLE orders
    ADD COLUMN refunded_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_orders_refunded_amount_range
        CHECK (refunded_amount >= 0 AND refunded_amount <= total_amount);
