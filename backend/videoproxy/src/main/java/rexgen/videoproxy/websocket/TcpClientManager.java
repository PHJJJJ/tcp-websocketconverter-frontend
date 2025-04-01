package rexgen.videoproxy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import rexgen.videoproxy.tcp.RwcVaTcpClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
class TcpClientManager {
    private static final Logger LOGGER = Logger.getLogger(TcpClientManager.class.getName());

    private final Map<String, RwcVaTcpClient> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get or create a TCP client for a session
     */
    public RwcVaTcpClient getClient(WebSocketSession session, String serverIp, int serverPort) {
        String sessionId = session.getId();

        return clients.computeIfAbsent(sessionId, id -> {
            LOGGER.info("Creating new TCP client for session: " + sessionId + " to " + serverIp + ":" + serverPort);

            RwcVaTcpClient client = new RwcVaTcpClient(sessionId, serverIp, serverPort);

            // Default error handler
            client.setOnInternalError(error -> {
                try {
                    Map<String, Object> message = new HashMap<>();
                    message.put("type", "error");
                    message.put("message", error.getMessage());

                    session.sendMessage(new TextMessage(
                            objectMapper.writeValueAsString(message)
                    ));
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error sending error message", e);
                }
            });

            return client;
        });
    }

    /**
     * Close and remove a client for a session
     */
    public void removeClient(WebSocketSession session) {
        String sessionId = session.getId();
        RwcVaTcpClient client = clients.remove(sessionId);

        if (client != null) {
            LOGGER.info("Closing TCP client for session: " + sessionId);
            client.close();
        }
    }
}