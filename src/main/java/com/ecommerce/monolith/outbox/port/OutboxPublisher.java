package com.ecommerce.monolith.outbox.port;

/**
 * 저장된 Outbox 이벤트를 외부 전송 수단으로 전달하는 출력 포트다.
 *
 * <p>호출 측은 로그, 메시지 브로커 같은 구체적인 전송 기술 대신 이 계약에만 의존한다.</p>
 */
@FunctionalInterface
public interface OutboxPublisher {

    void publish(OutboxMessage message);
}
