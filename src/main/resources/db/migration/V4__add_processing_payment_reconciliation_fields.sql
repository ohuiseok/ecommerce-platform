ALTER TABLE payment_reconciliation_tasks
    ADD COLUMN payment_id BIGINT,
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN payment_method VARCHAR(255);

ALTER TABLE payment_reconciliation_tasks
    ALTER COLUMN pg_event_id DROP NOT NULL,
    ALTER COLUMN pg_delivery_id DROP NOT NULL,
    ALTER COLUMN pg_transaction_id DROP NOT NULL,
    ALTER COLUMN pg_occurred_at DROP NOT NULL;

ALTER TABLE payment_reconciliation_tasks
    ADD CONSTRAINT uk_payment_reconciliation_tasks_payment_id UNIQUE (payment_id);
