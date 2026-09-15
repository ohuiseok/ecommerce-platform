package com.ecommerce.monolith.order;

import com.ecommerce.monolith.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 회원가입 -> 로그인 -> 관리자 상품 등록 -> 장바구니 담기 -> 체크아웃 -> 결제까지
 * 실제 HTTP 계층과 PostgreSQL을 통해 검증하는 전체 흐름 테스트.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.email=admin@test.com",
        "app.admin.password=admin1234",
        "app.admin.name=Test Admin"
})
class OrderCheckoutIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void checkoutFromCartThenPaySuccessfullyConfirmsOrder() throws Exception {
        String userToken = registerAndLogin("buyer@example.com");
        String adminToken = login("admin@test.com", "admin1234");

        Long productId = createProduct(adminToken, "무선 이어폰", 100000, 5);

        addToCart(userToken, productId, 2);

        Long orderId = checkout(userToken);

        Long paymentId = pay(userToken, orderId, "CARD", "4111111111111112"); // 짝수로 끝나는 카드번호 -> 승인

        mockMvc.perform(get("/api/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/products/{productId}/stock", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(3));

        mockMvc.perform(get("/api/payments/{paymentId}", paymentId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 이미 결제된 주문에 대한 재결제는 거부되어야 한다
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "method": "CARD", "idempotencyKey": "second-payment-key-%d", "cardNumber": "4111111111111112"}
                                """.formatted(orderId, orderId)))
                .andExpect(status().isConflict());
    }

    @Test
    void checkoutFromCartThenFailedPaymentCancelsOrderAndRestoresStock() throws Exception {
        String userToken = registerAndLogin("buyer2@example.com");
        String adminToken = login("admin@test.com", "admin1234");

        Long productId = createProduct(adminToken, "기계식 키보드", 150000, 3);

        addToCart(userToken, productId, 1);
        Long orderId = checkout(userToken);

        pay(userToken, orderId, "CARD", "4111111111111111"); // 홀수로 끝나는 카드번호 -> 거절

        mockMvc.perform(get("/api/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/products/{productId}/stock", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(3));

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "method": "CARD", "idempotencyKey": "retry-after-failure-%d", "cardNumber": "4111111111111112"}
                                """.formatted(orderId, orderId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkoutWithCouponThenFailedPaymentCancelsOrderAndRestoresResources() throws Exception {
        String userToken = registerAndLogin("buyer-with-coupon@example.com");
        String adminToken = login("admin@test.com", "admin1234");

        Long productId = createProduct(adminToken, "게이밍 마우스", 50000, 4);
        Long couponId = createCoupon(adminToken, "FAILPAY5000", 5000, 10000, 10);
        Long userCouponId = issueCoupon(userToken, couponId);

        addToCart(userToken, productId, 2);
        Long orderId = checkout(userToken, userCouponId, 100000, 5000, 95000);

        pay(userToken, orderId, "CARD", "4111111111111111");

        mockMvc.perform(get("/api/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/products/{productId}/stock", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(4));

        mockMvc.perform(get("/api/coupons/my")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userCouponId").value(userCouponId))
                .andExpect(jsonPath("$[0].status").value("ISSUED"))
                .andExpect(jsonPath("$[0].orderId").doesNotExist());
    }

    @Test
    void checkoutFailsWhenCartIsEmpty() throws Exception {
        String userToken = registerAndLogin("buyer3@example.com");

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shippingAddress": {"zipCode": "12345", "address": "Seoul", "recipientName": "Buyer", "recipientPhone": "010-0000-0000"}}
                                """))
                .andExpect(status().isConflict());
    }

    private String registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123", "name": "Buyer", "phoneNumber": "010-1234-5678"}
                                """.formatted(email)))
                .andExpect(status().isOk());

        return login(email, "password123");
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private Long createProduct(String adminToken, String name, int price, int stock) throws Exception {
        String response = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "price": %d, "stockQuantity": %d, "category": "electronics", "brand": "TestBrand"}
                                """.formatted(name, price, stock)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("productId").asLong();
    }

    private Long createCoupon(String adminToken, String code, int discountValue, int minOrderAmount, int issueLimit) throws Exception {
        String validFrom = LocalDateTime.now().minusDays(1).format(ISO);
        String validUntil = LocalDateTime.now().plusDays(30).format(ISO);

        String response = mockMvc.perform(post("/api/coupons")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "discountType": "FIXED_AMOUNT", "discountValue": %d,
                                 "minOrderAmount": %d, "validFrom": "%s", "validUntil": "%s", "issueLimit": %d}
                                """.formatted(code, code, discountValue, minOrderAmount, validFrom, validUntil, issueLimit)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("couponId").asLong();
    }

    private Long issueCoupon(String userToken, Long couponId) throws Exception {
        String response = mockMvc.perform(post("/api/coupons/{couponId}/issue", couponId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("userCouponId").asLong();
    }

    private void addToCart(String userToken, Long productId, int quantity) throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": %d}
                                """.formatted(productId, quantity)))
                .andExpect(status().isOk());
    }

    private Long checkout(String userToken) throws Exception {
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shippingAddress": {"zipCode": "12345", "address": "Seoul", "recipientName": "Buyer", "recipientPhone": "010-1234-5678"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("orderId").asLong()).isPositive();
        return json.get("orderId").asLong();
    }

    private Long checkout(
            String userToken,
            Long userCouponId,
            int originalAmount,
            int discountAmount,
            int totalAmount
    ) throws Exception {
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shippingAddress": {"zipCode": "12345", "address": "Seoul", "recipientName": "Buyer", "recipientPhone": "010-1234-5678"}, "userCouponId": %d}
                                """.formatted(userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.originalAmount").value(originalAmount))
                .andExpect(jsonPath("$.discountAmount").value(discountAmount))
                .andExpect(jsonPath("$.totalAmount").value(totalAmount))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("orderId").asLong()).isPositive();
        return json.get("orderId").asLong();
    }

    private Long pay(String userToken, Long orderId, String method, String cardNumber) throws Exception {
        String response = mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "method": "%s", "idempotencyKey": "payment-key-%d", "cardNumber": "%s"}
                                """.formatted(orderId, method, orderId, cardNumber)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("paymentId").asLong();
    }
}
