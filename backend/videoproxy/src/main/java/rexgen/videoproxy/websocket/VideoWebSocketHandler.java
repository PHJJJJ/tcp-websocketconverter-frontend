package rexgen.videoproxy.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import rexgen.videoproxy.protocol.*;
import rexgen.videoproxy.tcp.RwcVaTcpClient;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocket handler for video streams
 */
@Component
class VideoWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOGGER = Logger.getLogger(VideoWebSocketHandler.class.getName());

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService videoRefreshExecutor = Executors.newSingleThreadScheduledExecutor();

    private final TcpClientManager tcpClientManager;
    private final Map<String, List<Integer>> sessionCameraIds = new ConcurrentHashMap<>();

    public VideoWebSocketHandler(TcpClientManager tcpClientManager) {
        this.tcpClientManager = tcpClientManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LOGGER.info("New video WebSocket connection: " + session.getId());
        sessions.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LOGGER.info("Video WebSocket connection closed: " + session.getId());
        sessions.remove(session.getId());
        sessionCameraIds.remove(session.getId());
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
                case "liveInfo":
                    handleLiveInfoRequest(session, request);
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

        LOGGER.info("Handling video connect request to " + serverIp + ":" + serverPort);

        RwcVaTcpClient client = tcpClientManager.getClient(session, serverIp, serverPort);

        // Set up LiveData handler first
        client.setOnLiveData(liveData -> {
            try {
                // Send metadata as text message
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("type", "liveData");
                metadata.put("cameraId", liveData.getCameraId());
                metadata.put("timestamp", liveData.getTimestamp().getTime());
                metadata.put("codec", liveData.getCodec().name());
                metadata.put("intraCode", liveData.getIntraCode().name());
                metadata.put("endpoint", "video"); // 추가

                // Add object detection info
                List<Map<String, Object>> objects = new ArrayList<>();
                for (ObjectInfo obj : liveData.getObjects()) {
                    Map<String, Object> objMap = new HashMap<>();
                    objMap.put("type", obj.getType().name());
                    objMap.put("x", obj.getX());
                    objMap.put("y", obj.getY());
                    objMap.put("width", obj.getWidth());
                    objMap.put("height", obj.getHeight());
                    objMap.put("detectionScore", obj.getDetectionScore());
                    objects.add(objMap);
                }
                metadata.put("objects", objects);

                LOGGER.info("📤 Sending metadata for camera " + liveData.getCameraId() +
                        ", objects: " + liveData.getObjects().size() +
                        ", dataSize: " + (liveData.getData() != null ? liveData.getData().length : 0));

                session.sendMessage(new TextMessage(
                        objectMapper.writeValueAsString(metadata)
                ));

                // Send video data as binary message
                if (liveData.getData() != null && liveData.getData().length > 0) {
                    // 수정된 코드: 여유 있게 할당 (최소 1KB 추가). 여유 안 주면 에러 발생
                    ByteBuffer buffer = ByteBuffer.allocate(8 + liveData.getData().length + 1024);


                    // Include codec and extraDataSize in binary header
                    buffer.putInt(liveData.getCodec().getValue());
                    buffer.putInt(liveData.getExtraDataSize());
                    // Add extra data if present
                    if (liveData.getExtraData() != null && liveData.getExtraData().length > 0) {
                        // Resize buffer if needed
                        if (buffer.remaining() < liveData.getExtraData().length) {
                            ByteBuffer newBuffer = ByteBuffer.allocate(
                                    buffer.position() + liveData.getExtraData().length + liveData.getData().length
                            );
                            buffer.flip();
                            newBuffer.put(buffer);
                            buffer = newBuffer;
                        }

                        buffer.put(liveData.getExtraData());
                    }

                    // Add video data
                    buffer.put(liveData.getData());

                    buffer.flip();
                    LOGGER.info("📤 Sending video binary data: " + buffer.remaining() + " bytes");
                    session.sendMessage(new BinaryMessage(buffer));
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error sending live data", e);
            }
        });

        // Set up connection change handler
        client.setOnConnectChange(connected -> {
            try {
                LOGGER.info("Video TCP connection status changed: " + connected);
                Map<String, Object> connectionMessage = new HashMap<>();
                connectionMessage.put("type", "connection");
                connectionMessage.put("connected", connected);
                connectionMessage.put("endpoint", "video"); // 추가

                session.sendMessage(new TextMessage(
                        objectMapper.writeValueAsString(connectionMessage)
                ));

                // If connection established, send init command
                if (connected) {
                    boolean sent = client.sendInitConnect(RwcVaEnums.ConnectType.LIVE);
                    if (sent) {
                        LOGGER.info("Sent video init connect after connection established");
                    } else {
                        LOGGER.warning("Failed to send video init connect");
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error sending connection status", e);
            }
        });

        // Set up init response handler
        client.setInitResponseHandler(initSuccess -> {
            try {
                if (initSuccess) {
                    LOGGER.info("Video connection initialized with valid clientKey: " + client.getServerClientKey());

                    // Send connectionReady message after initialization
                    Map<String, Object> readyMessage = new HashMap<>();
                    readyMessage.put("type", "connectionReady");
                    readyMessage.put("connected", true);
                    readyMessage.put("clientKey", client.getServerClientKey());
                    readyMessage.put("endpoint", "video"); // 추가

                    session.sendMessage(new TextMessage(
                            objectMapper.writeValueAsString(readyMessage)
                    ));
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error sending connection ready message", e);
            }
        });

        // Connect to TCP server
        client.connect().thenAccept(connected -> {
            LOGGER.info("Video TCP client connected successfully: " + connected);

            // Setup periodic refresh for active sessions
            setupPeriodicRefresh(session, client);
        });
    }

    /**
     * Handle live info request
     */
    private void handleLiveInfoRequest(WebSocketSession session, Map<String, Object> request) {
        List<Integer> cameraIds = (List<Integer>) request.get("cameraIds");

        if (cameraIds == null || cameraIds.isEmpty()) {
            LOGGER.warning("No camera IDs provided for live info request");
            return;
        }

        // Store camera IDs for this session
        sessionCameraIds.put(session.getId(), new ArrayList<>(cameraIds));

        String serverIp = (String) request.get("serverIp");
        int serverPort = ((Number) request.get("serverPort")).intValue();

        RwcVaTcpClient client = tcpClientManager.getClient(session, serverIp, serverPort);

        if (client.isConnected()) {
            // Only send if client has valid clientKey
            if (client.getServerClientKey() != 0) {
                long clientKey = client.getServerClientKey();
                LOGGER.info("Sending live info request for cameras: " + cameraIds + " with clientKey: " + clientKey);
                boolean sent = client.sendLiveInfo(cameraIds);
                if (!sent) {
                    LOGGER.warning("Failed to send live info request");

                    // 실패 메시지 전송
                    try {
                        Map<String, Object> errorMessage = new HashMap<>();
                        errorMessage.put("type", "error");
                        errorMessage.put("message", "Failed to send live info request");
                        errorMessage.put("endpoint", "video");

                        session.sendMessage(new TextMessage(
                                objectMapper.writeValueAsString(errorMessage)
                        ));
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "Error sending error message", e);
                    }
                }
            } else {
                LOGGER.warning("Client has no valid clientKey, cannot send LiveInfo request");
                // Try to re-initialize connection
                boolean sent = client.sendInitConnect(RwcVaEnums.ConnectType.LIVE);
                if (sent) {
                    LOGGER.info("Re-initializing connection for live info request");
                }
            }
        } else {
            LOGGER.warning("Client not connected for session: " + session.getId());

            // Try to connect then send request
            client.connect().thenAccept(connected -> {
                if (connected) {
                    // Set up a one-time handler to send LiveInfo after initialization
                    Consumer<Boolean> oneTimeHandler = new Consumer<Boolean>() {
                        @Override
                        public void accept(Boolean initSuccess) {
                            if (initSuccess) {
                                LOGGER.info("Sending delayed live info request for cameras: " + cameraIds);
                                boolean sent = client.sendLiveInfo(cameraIds);
                                if (!sent) {
                                    LOGGER.warning("Failed to send delayed live info request");
                                }
                            }
                            client.removeInitResponseHandler(this);
                        }
                    };

                    client.setInitResponseHandler(oneTimeHandler);
                    client.sendInitConnect(RwcVaEnums.ConnectType.LIVE);
                } else {
                    LOGGER.warning("Failed to connect for delayed live info request");
                }
            });
        }
    }

    /**
     * Set up periodic refresh for active camera streams
     */
    private void setupPeriodicRefresh(WebSocketSession session, RwcVaTcpClient client) {
        String sessionId = session.getId();

        // Schedule periodic LiveInfo refresh task
        videoRefreshExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!sessions.containsKey(sessionId)) {
                    return; // Session closed
                }

                List<Integer> cameraIds = sessionCameraIds.get(sessionId);
                if (cameraIds == null || cameraIds.isEmpty()) {
                    return; // No active cameras
                }

                if (client.isConnected() && client.getServerClientKey() != 0) {
                    LOGGER.info("Periodic refresh: Sending live info request for cameras: " + cameraIds);
                    boolean sent = client.sendLiveInfo(cameraIds);
                    if (!sent) {
                        LOGGER.warning("Failed to send periodic live info refresh");
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error in periodic refresh task", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }
}