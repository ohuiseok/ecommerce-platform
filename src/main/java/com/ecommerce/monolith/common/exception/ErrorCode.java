package com.ecommerce.monolith.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다"),

    // Product
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다"),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 주문 상태입니다"),
    ORDER_STATE_CONFLICT(HttpStatus.CONFLICT, "주문 상태가 이미 변경되었습니다"),
    ORDER_CANCELLATION_NOT_ALLOWED(HttpStatus.CONFLICT, "배송 중이거나 완료된 주문은 취소할 수 없습니다"),

    // Cart
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다"),
    CART_EMPTY(HttpStatus.CONFLICT, "장바구니가 비어 있습니다"),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다"),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 결제입니다"),

    // Coupon
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다"),
    COUPON_NOT_IN_VALID_PERIOD(HttpStatus.CONFLICT, "발급 또는 사용 가능한 기간이 아닙니다"),
    COUPON_ISSUE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "쿠폰 발급 수량이 모두 소진되었습니다"),
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다"),
    USER_COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "보유한 쿠폰을 찾을 수 없습니다"),
    COUPON_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용되었거나 사용할 수 없는 쿠폰입니다"),
    COUPON_MIN_ORDER_AMOUNT_NOT_MET(HttpStatus.CONFLICT, "쿠폰 최소 주문 금액을 충족하지 않습니다"),

    // Review
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다"),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 리뷰를 작성한 상품입니다"),
    PRODUCT_NOT_PURCHASED(HttpStatus.CONFLICT, "구매 확정된 주문에 포함된 상품만 리뷰를 작성할 수 있습니다");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
