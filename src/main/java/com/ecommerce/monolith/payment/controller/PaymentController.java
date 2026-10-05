package com.ecommerce.monolith.payment.controller;

import com.ecommerce.monolith.order.dto.OrderResponse;
import com.ecommerce.monolith.order.service.OrderService;
import com.ecommerce.monolith.payment.dto.PaymentRequest;
import com.ecommerce.monolith.payment.dto.PaymentResponse;
import com.ecommerce.monolith.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payment", description = "모의 PG 연동 결제 요청/조회/취소 API")
public class PaymentController {

    private final PaymentService paymentService;
    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<PaymentResponse.PaymentInfo> requestPayment(
            @Valid @RequestBody PaymentRequest.Create request,
            Authentication authentication) {
        OrderResponse.OrderInfo order = orderService.getOrder(request.getOrderId());
        validateSelfOrAdmin(order.getUserId(), authentication);

        return ResponseEntity.ok(paymentService.requestPayment(request));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse.PaymentInfo> getPayment(
            @PathVariable Long paymentId,
            Authentication authentication) {
        PaymentResponse.PaymentInfo payment = paymentService.getPayment(paymentId);
        validateSelfOrAdmin(payment.getUserId(), authentication);

        return ResponseEntity.ok(payment);
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse.PaymentInfo> getPaymentByOrderId(
            @PathVariable Long orderId,
            Authentication authentication) {
        OrderResponse.OrderInfo order = orderService.getOrder(orderId);
        validateSelfOrAdmin(order.getUserId(), authentication);

        return ResponseEntity.ok(paymentService.getPaymentByOrderId(orderId));
    }

    @GetMapping("/reconciliation/mismatches")
    public ResponseEntity<List<PaymentResponse.PaymentOrderMismatchInfo>> getPaymentOrderMismatches() {
        return ResponseEntity.ok(paymentService.getPaymentOrderMismatches());
    }

    @GetMapping("/reconciliation/tasks")
    public ResponseEntity<List<PaymentResponse.PaymentReconciliationTaskInfo>> getOpenPaymentReconciliationTasks() {
        return ResponseEntity.ok(paymentService.getOpenPaymentReconciliationTasks());
    }

    @DeleteMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse.PaymentInfo> cancelPayment(@PathVariable Long paymentId) {
        return ResponseEntity.ok(paymentService.cancelPayment(paymentId));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Payment API is running");
    }

    private void validateSelfOrAdmin(Long resourceUserId, Authentication authentication) {
        Long authenticatedUserId = (Long) authentication.getPrincipal();
        if (!resourceUserId.equals(authenticatedUserId) && !isAdmin(authentication)) {
            throw new AccessDeniedException("접근 권한이 없습니다");
        }
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
