ALTER TABLE products
    ADD CONSTRAINT ck_products_stock_quantity_non_negative CHECK (stock_quantity >= 0);

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_total_amount_non_negative CHECK (total_amount >= 0),
    ADD CONSTRAINT ck_orders_original_amount_non_negative CHECK (original_amount >= 0),
    ADD CONSTRAINT ck_orders_discount_amount_non_negative CHECK (discount_amount >= 0);

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_amount_non_negative CHECK (amount >= 0);
