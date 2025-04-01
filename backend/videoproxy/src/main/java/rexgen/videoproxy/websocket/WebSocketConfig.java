package rexgen.videoproxy.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    // 생성자 주입 제거

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 공통 TcpClientManager 인스턴스 사용
        TcpClientManager manager = tcpClientManager();
        registry.addHandler(videoWebSocketHandler(manager), "/ws/video").setAllowedOrigins("*");
        registry.addHandler(controlWebSocketHandler(manager), "/ws/control").setAllowedOrigins("*");
    }

    @Bean
    public TcpClientManager tcpClientManager() {
        return new TcpClientManager();
    }

    @Bean
    public VideoWebSocketHandler videoWebSocketHandler(TcpClientManager tcpClientManager) {
        return new VideoWebSocketHandler(tcpClientManager);
    }

    @Bean
    public ControlWebSocketHandler controlWebSocketHandler(TcpClientManager tcpClientManager) {
        return new ControlWebSocketHandler(tcpClientManager);
    }
}