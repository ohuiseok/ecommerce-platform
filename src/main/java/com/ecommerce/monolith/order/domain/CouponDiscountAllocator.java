package com.ecommerce.monolith.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 주문 라인 입력 순서를 유지하며 원 단위 쿠폰 할인 스냅샷을 계산한다. */
public final class CouponDiscountAllocator {

    private CouponDiscountAllocator() {
    }

    public record Line(long orderItemId, BigDecimal grossAmount) {
    }

    public record Allocation(long orderItemId, BigDecimal grossAmount,
                             BigDecimal couponDiscountAmount, BigDecimal paymentAmount) {
    }

    public static List<Allocation> allocate(List<Line> lines, BigDecimal discountAmount) {
        Objects.requireNonNull(lines, "주문 라인이 필요합니다");
        BigDecimal discount = won(discountAmount);
        List<Line> inputs = List.copyOf(lines);
        var ids = new HashSet<Long>();
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : inputs) {
            if (!ids.add(line.orderItemId())) {
                throw new IllegalArgumentException("주문 라인 ID가 중복되었습니다");
            }
            total = total.add(won(line.grossAmount()));
        }
        if (discount.compareTo(total) > 0) {
            throw new IllegalArgumentException("쿠폰 할인액은 주문 원금을 초과할 수 없습니다");
        }

        List<BigDecimal> discounts = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (Line line : inputs) {
            BigDecimal share = total.signum() == 0 ? BigDecimal.ZERO
                    : discount.multiply(line.grossAmount()).divide(total, 0, RoundingMode.DOWN);
            discounts.add(share);
            allocated = allocated.add(share);
        }

        List<Integer> priority = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            priority.add(i);
        }
        priority.sort(Comparator.<Integer, BigDecimal>comparing(i -> inputs.get(i).grossAmount())
                .reversed().thenComparingLong(i -> inputs.get(i).orderItemId()));
        int remainder = discount.subtract(allocated).intValueExact();
        for (int i = 0; i < remainder; i++) {
            int index = priority.get(i);
            discounts.set(index, discounts.get(index).add(BigDecimal.ONE));
        }

        List<Allocation> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Line line = inputs.get(i);
            result.add(new Allocation(line.orderItemId(), line.grossAmount(), discounts.get(i),
                    line.grossAmount().subtract(discounts.get(i))));
        }
        return List.copyOf(result);
    }

    private static BigDecimal won(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("금액은 0 이상이어야 합니다");
        }
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("금액은 원 단위여야 합니다", exception);
        }
    }
}
