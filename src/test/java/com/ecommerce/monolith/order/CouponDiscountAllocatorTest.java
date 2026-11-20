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
