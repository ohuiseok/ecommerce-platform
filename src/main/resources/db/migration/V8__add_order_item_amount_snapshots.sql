-- 기존 라인의 할인 배분은 복원할 수 없으므로 스냅샷을 임의로 백필하지 않는다.
-- 주문 생성 시 스냅샷 확정은 할인 배분 계산기 도입 후 연결한다.
ALTER TABLE order_items
    ADD COLUMN line_gross_amount NUMERIC(19, 2),
    ADD COLUMN line_coupon_discount_amount NUMERIC(19, 2),
    ADD COLUMN line_payment_amount NUMERIC(19, 2);
