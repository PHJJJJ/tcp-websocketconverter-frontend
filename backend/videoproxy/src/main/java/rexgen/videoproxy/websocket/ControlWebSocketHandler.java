package rexgen.videoproxy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import rexgen.videoproxy.protocol.RwcVaEnums;
import rexgen.videoproxy.tcp.RwcVaTcpClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

class ControlWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOGGER = Logger.getLogger(ControlWebSocketHandler.class.getName());

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final TcpClientManager tcpClientManager;

    public ControlWebSocketHandler(TcpClientManager tcpClientManager) {
        this.tcpClientManager = tcpClientManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LOGGER.info("New control WebSocket connection: " + session.getId());
        sessions.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LOGGER.info("Control WebSocket connection closed: " + session.getId());
        sessions.remove(session.getId());
        tcpClientManager.removeClient(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<String, Object> request = objectMapper.readValue(message.getPayload(), Map.class);
            String type = (String) request.get("type");

            switch (type) {
                case "connect":
                    handleConnectRequest(session, request);
                    break;
                case "playbackInfo":
                    // Handle playback info request
                    break;
                case "playbackControl":
                    // Handle playback control request
                    break;
                default:
                    LOGGER.warning("Unknown request type: " + type);
                    break;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling text message", e);
        }
    }

    /**
     * Handle connect request
     */
    private void handleConnectRequest(WebSocketSession session, Map<String, Object> request) {
        String serverIp = (String) request.get("serverIp");
        int serverPort = ((Number) request.get("serverPort")).intValue();

        LOGGER.info("Handling control connect request to " + serverIp + ":" + serverPort);

        RwcVaTcpClient client = tcpClientManager.getClient(session, serverIp, serverPort);

        // Set up connection change handler with endpoint information
        client.setOnConnectChange(connected -> {
            try {
                LOGGER.info("Control TCP connection status changed: " + connected);
                Map<String, Object> connectionMessage = new HashMap<>();
                connectionMessage.put("type", "connection");
                connectionMessage.put("connected", connected);
                connectionMessage.put("endpoint", "control");

                session.sendMessage(new TextMessage(
                        objectMapper.writeValueAsString(connectionMessage)
                ));

                // If connection established, send init command
                if (connected) {
                    boolean sent = client.sendInitConnect(RwcVaEnums.ConnectType.PLAYBACK);
                    if (sent) {
                        LOGGER.info("Sent control init connect after connection established");
                    } else {
                        LOGGER.warning("Failed to send control init connect");
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error sending control connection status", e);
            }
        });

        // Set up init response handler
        client.setInitResponseHandler(initSuccess -> {
            try {
                if (initSuccess) {
                    LOGGER.info("Control connection initialized with valid clientKey: " + client.getServerClientKey());

                    // Send connectionReady message after initialization
                    Map<String, Object> readyMessage = new HashMap<>();
                    readyMessage.put("type", "connectionReady");
                    readyMessage.put("connected", true);
                    readyMessage.put("clientKey", client.getServerClientKey());
                    readyMessage.put("endpoint", "control");

                    session.sendMessage(new TextMessage(
                            objectMapper.writeValueAsString(readyMessage)
                    ));
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error sending control connection ready message", e);
            }
        });

        // Connect to TCP server
        client.connect().thenAccept(connected -> {
            LOGGER.info("Control TCP client connected: " + connected);
        });
    }
}