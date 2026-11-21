package com.ecommerce.monolith.order;

import com.ecommerce.monolith.order.domain.CouponDiscountAllocator;
import com.ecommerce.monolith.order.domain.CouponDiscountAllocator.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CouponDiscountAllocatorTest {

    @Test
    void allocatesDiscountInProportionToGrossAmountsInInputOrder() {
        var result = CouponDiscountAllocator.allocate(List.of(
                new Line(2, new BigDecimal("10000.00")),
                new Line(1, new BigDecimal("20000.00"))), new BigDecimal("6000"));

        assertThat(result).extracting(CouponDiscountAllocator.Allocation::orderItemId)
                .containsExactly(2L, 1L);
        assertThat(result.get(0).couponDiscountAmount()).isEqualByComparingTo("2000");
        assertThat(result.get(1).couponDiscountAmount()).isEqualByComparingTo("4000");
        assertThat(result.get(0).paymentAmount()).isEqualByComparingTo("8000");
        assertThat(result.get(1).paymentAmount()).isEqualByComparingTo("16000");
    }

    @Test
    void assignsOneWonRemainderToLargestGrossAmount() {
        var result = CouponDiscountAllocator.allocate(List.of(
                new Line(1, new BigDecimal("10000")),
                new Line(2, new BigDecimal("20000"))), new BigDecimal("5000"));

        assertThat(result.get(0).couponDiscountAmount()).isEqualByComparingTo("1666");
        assertThat(result.get(1).couponDiscountAmount()).isEqualByComparingTo("3334");
        assertThat(result.get(0).paymentAmount()).isEqualByComparingTo("8334");
        assertThat(result.get(1).paymentAmount()).isEqualByComparingTo("16666");
    }

    @Test
    void distributesMultipleRemainingWonByGrossAmountThenAscendingId() {
        var result = CouponDiscountAllocator.allocate(List.of(
                new Line(3, new BigDecimal("10")),
                new Line(4, new BigDecimal("20")),
                new Line(2, new BigDecimal("20"))), new BigDecimal("3"));

        // 기본 배분은 0, 1, 1원이고 남은 1원은 원금이 같은 라인 중 ID 2에 배분한다.
        assertThat(result).extracting(CouponDiscountAllocator.Allocation::orderItemId)
                .containsExactly(3L, 4L, 2L);
        assertThat(result.get(0).couponDiscountAmount()).isEqualByComparingTo("0");
        assertThat(result.get(1).couponDiscountAmount()).isEqualByComparingTo("1");
        assertThat(result.get(2).couponDiscountAmount()).isEqualByComparingTo("2");

        var tied = CouponDiscountAllocator.allocate(List.of(
                new Line(3, BigDecimal.ONE),
                new Line(1, BigDecimal.ONE),
                new Line(2, BigDecimal.ONE)), new BigDecimal("2"));

        // 남은 2원을 서로 다른 라인에 1원씩 배분한다.
        assertThat(tied.get(0).couponDiscountAmount()).isEqualByComparingTo("0");
        assertThat(tied.get(1).couponDiscountAmount()).isEqualByComparingTo("1");
        assertThat(tied.get(2).couponDiscountAmount()).isEqualByComparingTo("1");
    }

    @Test
    void preservesMoneyInvariantsForEveryDiscountAcrossSmallOrders() {
        for (int first = 0; first <= 6; first++) {
            for (int second = 0; second <= 6; second++) {
                for (int third = 0; third <= 6; third++) {
                    var lines = List.of(new Line(3, BigDecimal.valueOf(first)),
                            new Line(1, BigDecimal.valueOf(second)),
                            new Line(2, BigDecimal.valueOf(third)));
                    BigDecimal grossTotal = BigDecimal.valueOf(first + second + third);
                    for (int discount = 0; discount <= first + second + third; discount++) {
                        BigDecimal couponTotal = BigDecimal.valueOf(discount);
                        var result = CouponDiscountAllocator.allocate(lines, couponTotal);
                        String scenario = "gross=%s, discount=%s".formatted(lines, couponTotal);

                        assertThat(result).as(scenario).hasSize(lines.size());
                        BigDecimal discountSum = BigDecimal.ZERO;
                        BigDecimal paymentSum = BigDecimal.ZERO;
                        for (int i = 0; i < result.size(); i++) {
                            var allocation = result.get(i);
                            assertThat(allocation.orderItemId()).as(scenario).isEqualTo(lines.get(i).orderItemId());
                            assertThat(allocation.grossAmount()).as(scenario)
                                    .isEqualByComparingTo(lines.get(i).grossAmount());
                            assertThat(allocation.couponDiscountAmount()).as(scenario)
                                    .isBetween(BigDecimal.ZERO, allocation.grossAmount());
                            assertThat(allocation.couponDiscountAmount().stripTrailingZeros().scale())
                                    .as(scenario).isLessThanOrEqualTo(0);
                            assertThat(allocation.paymentAmount()).as(scenario).isNotNegative();
                            assertThat(allocation.paymentAmount().add(allocation.couponDiscountAmount()))
                                    .as(scenario).isEqualByComparingTo(allocation.grossAmount());
                            discountSum = discountSum.add(allocation.couponDiscountAmount());
                            paymentSum = paymentSum.add(allocation.paymentAmount());
                        }
                        assertThat(discountSum).as(scenario).isEqualByComparingTo(couponTotal);
                        assertThat(paymentSum).as(scenario).isEqualByComparingTo(grossTotal.subtract(couponTotal));
                    }
                }
            }
        }
    }

    @Test
    void supportsZeroAmountOrdersWithoutDivisionByZero() {
        var result = CouponDiscountAllocator.allocate(List.of(new Line(1, BigDecimal.ZERO)), BigDecimal.ZERO);
        assertThat(result.get(0).paymentAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(CouponDiscountAllocator.allocate(List.of(), BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void rejectsInvalidMoneyAndDuplicateLineIds() {
        var lines = List.of(new Line(1, BigDecimal.TEN));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CouponDiscountAllocator.allocate(lines, new BigDecimal("11")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CouponDiscountAllocator.allocate(lines, new BigDecimal("-1")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CouponDiscountAllocator.allocate(lines, new BigDecimal("0.5")));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CouponDiscountAllocator.allocate(List.of(new Line(1, new BigDecimal("-1"))), BigDecimal.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() ->
                CouponDiscountAllocator.allocate(List.of(lines.get(0), lines.get(0)), BigDecimal.ZERO));
    }
}
