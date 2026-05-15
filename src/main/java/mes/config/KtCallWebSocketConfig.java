package mes.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * KT 전화 수신 실시간 알림용 WebSocket 설정
 * - 브라우저(SockJS) ↔ Spring STOMP
 */
@Configuration
@EnableWebSocketMessageBroker
public class KtCallWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 브라우저 구독 prefix: /topic/...
        config.enableSimpleBroker("/topic");
        // 서버로 메시지 보낼 때 prefix: /app/...
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 브라우저 접속 엔드포인트 (SockJS 폴백 포함)
        registry.addEndpoint("/ws/kt-call")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
