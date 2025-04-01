package rexgen.videoproxy.tcp;

import rexgen.videoproxy.protocol.*;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP client for communicating with RexWatch server
 */
public class RwcVaTcpClient {
    private static final Logger LOGGER = Logger.getLogger(RwcVaTcpClient.class.getName());

    private String serverIp;
    private int serverPort;
    private String clientKey;
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isConnected = false;
    private boolean isDisposed = false;
    private long serverClientKey = 0;
    private byte[] rawServerClientKey = null; // 추가: 원본 바이트 배열 형태로 저장
    private RwcVaEnums.Version vaVersion = RwcVaEnums.Version.v40;

    private ExecutorService receiveExecutor;
    private ScheduledExecutorService heartbeatExecutor;
    private int sendFlag = 0;
    private boolean isLiveSignalEnabled = true;

    private List<byte[]> liveDataBuffer = new ArrayList<>();
    private List<byte[]> playbackDataBuffer = new ArrayList<>();

    // 초기화 응답 핸들러
    private List<Consumer<Boolean>> initResponseHandlers = new CopyOnWriteArrayList<>();

    // Event handlers
    private Consumer<Throwable> onInternalError;
    private Consumer<Boolean> onConnectChange;
    private Consumer<LiveDataInfo> onLiveData;

    // 동시 재연결 시도 방지를 위한 플래그 추가
    private volatile boolean isReconnecting = false;
    private final Object reconnectLock = new Object();
    private ScheduledFuture<?> reconnectFuture = null;


    // 연결 시도 간의 딜레이를 위한 지수 백오프 관련 필드
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long MAX_RECONNECT_DELAY_MS = 30000; // 최대 30초

    // 데이터 수신 상태 추적용 필드 추가
    private volatile long lastDataReceivedTime = 0;
    private volatile int totalMessagesReceived = 0;

    public RwcVaTcpClient(String clientKey, String serverIp, int serverPort) {
        this.clientKey = clientKey;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.receiveExecutor = Executors.newSingleThreadExecutor();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Connect to the RexWatch server with connection lock
     */
    public CompletableFuture<Boolean> connect() {
        // 재연결 중이면 기존 Future 반환
        synchronized (reconnectLock) {
            if (isReconnecting) {
                LOGGER.log(Level.INFO, "Connect already in progress, skipping duplicate request");
                CompletableFuture<Boolean> result = new CompletableFuture<>();
                result.complete(false);
                return result;
            }
            isReconnecting = true;
        }

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                if (isConnected) {
                    return true;
                }

                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Error closing existing socket", e);
                    }
                    socket = null;
                }

                LOGGER.log(Level.INFO, "Connecting to server {0}:{1}", new Object[] { serverIp, serverPort });
                socket = new Socket(serverIp, serverPort);
                socket.setSoTimeout(0); // 타임아웃 없음
                socket.setKeepAlive(false); // TCP keepalive 활성화
                socket.setTcpNoDelay(false); // Nagle 알고리즘 비활성화

                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                isConnected = true;
                reconnectAttempts = 0; // 성공 시 재연결 시도 카운터 초기화

                if (onConnectChange != null) {
                    onConnectChange.accept(true);
                }

                // Start heartbeat
                startHeartbeat();

                // Start receive thread
                startReceive();

                return true;
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to connect to server", e);
                isConnected = false;

                if (onConnectChange != null) {
                    onConnectChange.accept(false);
                }
                return false;
            }
        }).whenComplete((result, ex) -> {
            // 연결 시도 종료 시 항상 플래그 초기화
            synchronized (reconnectLock) {
                isReconnecting = false;
            }
        });

        return future;
    }

    /**
     * Disconnect from the RexWatch server
     */
    public void disconnect() {
        if (!isConnected) {
            return;
        }

        isConnected = false;

        // 재연결 작업 취소
        synchronized (reconnectLock) {
            isReconnecting = false;
            if (reconnectFuture != null && !reconnectFuture.isDone()) {
                reconnectFuture.cancel(false);
                reconnectFuture = null;
            }
        }

        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing socket", e);
        }

        if (onConnectChange != null) {
            onConnectChange.accept(false);
        }

        liveDataBuffer.clear();
        playbackDataBuffer.clear();
    }

    /**
     * Close and dispose the client
     */
    public void close() {
        if (isDisposed) {
            return;
        }

        isDisposed = true;

        // Shutdown heartbeat executor
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }

        disconnect();
        receiveExecutor.shutdown();
    }

    /**
     * Start the receive thread
     */
    private void startReceive() {
        receiveExecutor.submit(this::receiveThread);
    }

    /**
     * Start heartbeat timer with increased interval
     */
    private void startHeartbeat() {
        if (heartbeatExecutor.isShutdown()) {
            LOGGER.warning("Cannot start heartbeat: executor is shutdown");
            return;
        }

        // 기존 작업 취소
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            reconnectFuture.cancel(false);
        }

        final Runnable heartbeatTask = new Runnable() {
            @Override
            public void run() {
                try {
                    if (isConnected && isLiveSignalEnabled) {
                        if (sendFlag >= 3) { // 10에서 3으로 변경하여 더 자주 신호 보내기
                            sendFlag = 0;
                            boolean sent = sendLiveSignal();
                            if (sent) {
                                LOGGER.info("Sending live signal to keep connection alive"); // 로그 수준 높임
                            } else {
                                LOGGER.warning("Failed to send live signal");
                            }
                        } else {
                            sendFlag++;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error in heartbeat task", e);
                }
            }
        };

        try {
            heartbeatExecutor.scheduleAtFixedRate(
                    heartbeatTask,
                    0,
                    5, // 5초로 증가 (기존 1초)
                    TimeUnit.SECONDS
            );
            LOGGER.info("Heartbeat scheduler started with 5-second interval");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start heartbeat scheduler", e);
        }
    }

    /**
     * Send live signal to keep connection alive
     */
    private boolean sendLiveSignal() {
        try {
            if (!isConnected) {
                LOGGER.warning("Cannot send live signal: not connected. Attempting reconnect...");
                scheduleReconnect();
                return false;
            }

            // 패킷 구조 및 값 확인 - 정확한 enum 값 확인
            byte command = 100; // LIVE_SIGNAL의 값
            byte subCommand = 0;

            LOGGER.info("Sending live signal - command: " + command + ", subCommand: " + subCommand);

            return sendMessage(command, subCommand, null);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending live signal", e);

            // 소켓 오류 시 연결 상태 업데이트 및 재연결 시도
            isConnected = false;
            if (onConnectChange != null) {
                onConnectChange.accept(false);
            }

            // 비동기 재연결 시도
            scheduleReconnect();

            return false;
        }
    }

    /**
     * Main receive thread method with improved error handling and monitoring
     */
    private void receiveThread() {
        byte[] headBuffer = new byte[RwcVaConstants.HEAD];
        byte[] bodySizeBuffer = new byte[4];

        LOGGER.info("🔄 Started receive thread for client " + clientKey + " to " + serverIp + ":" + serverPort);

        while (!isDisposed && isConnected) {
            try {
                // 소켓이 여전히 연결되어 있는지 확인
                if (socket == null || !socket.isConnected()) {
                    LOGGER.warning("Socket disconnected, attempting to reconnect");
                    scheduleReconnect();
                    break; // 재연결은 새 스레드로 진행, 현재 스레드 종료
                }

                // 비블로킹 방식으로 데이터 확인
                int available = 0;
                try {
                    available = inputStream.available();

                    // 주기적으로 상태 로깅 추가 (30초마다)
                    long now = System.currentTimeMillis();
                    if (now - lastDataReceivedTime > 30000) {
                        LOGGER.info("📊 Receive thread stats - Messages received: " + totalMessagesReceived +
                                ", Last data received: " + (lastDataReceivedTime > 0 ?
                                ((now - lastDataReceivedTime) / 1000) + " seconds ago" : "never") +
                                ", Available bytes: " + available);
                        lastDataReceivedTime = now; // 로깅 타임스탬프 업데이트
                    }

                } catch (IOException e) {
                    LOGGER.warning("Error checking available data: " + e.getMessage());
                    scheduleReconnect();
                    break;
                }

                if (available > 0) {
                    // 데이터가 있을 때만 읽기 시도
                    Arrays.fill(headBuffer, (byte) 0);

                    // 헤더 읽기 시도 로그 추가
                    LOGGER.info("📥 Attempting to read header from " + available + " available bytes");

                    // 헤더 읽기
                    int bytesRead = readFully(inputStream, headBuffer, 0, headBuffer.length);
                    if (bytesRead <= 0) {
                        LOGGER.warning("Failed to read header, reconnecting");
                        scheduleReconnect();
                        break;
                    }

                    // 헤더 처리
                    RexMessageHeader header = RexMessageHeader.fromBytes(headBuffer);
                    LOGGER.info("📨 Received header: prefix=" + header.getPrefix() +
                            ", command=" + header.getCommand() +
                            ", subCommand=" + header.getSubCommand() +
                            ", size=" + header.getPacketSize() +
                            ", clientKey=" + header.getClientKey());

                    // 프리픽스 확인
                    if (!header.getPrefix().equals(RwcVaConstants.DEFAULT_PREFIX)) {
                        LOGGER.warning("Invalid prefix: " + header.getPrefix());
                        scheduleReconnect();
                        break;
                    }

                    // 본문 크기 읽기
                    int bodySize = 0;
                    if (header.getPacketSize() > headBuffer.length) {
                        bytesRead = readFully(inputStream, bodySizeBuffer, 0, bodySizeBuffer.length);
                        if (bytesRead <= 0) {
                            scheduleReconnect();
                            break;
                        }

                        bodySize = ByteBuffer.wrap(bodySizeBuffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        LOGGER.info("📦 Received body size: " + bodySize + " bytes");

                        // 본문 크기 확인
                        if (header.getPacketSize() - headBuffer.length - bodySizeBuffer.length != bodySize) {
                            LOGGER.warning("Packet size mismatch: " + header.getPacketSize() + " vs " +
                                    (bodySize + headBuffer.length + bodySizeBuffer.length));
                            scheduleReconnect();
                            break;
                        }
                    }

                    // 본문 데이터 읽기
                    byte[] bodyBuffer = null;
                    if (bodySize > 0) {
                        bodyBuffer = new byte[bodySize];
                        bytesRead = readFully(inputStream, bodyBuffer, 0, bodySize);
                        if (bytesRead <= 0) {
                            LOGGER.warning("Failed to read body data, expected " + bodySize + " bytes");
                            scheduleReconnect();
                            break;
                        }
                        LOGGER.info("📄 Read body data: " + bytesRead + " bytes");
                    }

                    // 데이터 수신 시간 및 카운터 업데이트
                    lastDataReceivedTime = System.currentTimeMillis();
                    totalMessagesReceived++;

                    // 명령 처리 전 로깅 추가
                    LOGGER.info("🔄 Processing message: command=" + header.getCommand() +
                            ", subCommand=" + header.getSubCommand());

                    // 메시지 처리
                    processMessage(header.getCommand(), header.getSubCommand(), bodyBuffer);
                } else {
                    // 데이터가 없으면 짧게 대기 (CPU 사용량 감소)
                    Thread.sleep(100); // 50ms -> 100ms로 증가
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.info("Receive thread interrupted");
                break;
            } catch (SocketTimeoutException e) {
                // 타임아웃은 정상적인 상황으로 간주하고 계속 진행
                LOGGER.info("Socket read timeout, continuing..."); // 로그 수준 높임
                continue;
            } catch (SocketException e) {
                // 소켓 예외 발생 시 재연결 시도
                if (!isDisposed) {
                    LOGGER.log(Level.SEVERE, "Socket error: " + e.getMessage());
                    scheduleReconnect();
                    break; // 현재 스레드 종료
                }
            } catch (Exception e) {
                if (!isDisposed) {
                    LOGGER.log(Level.SEVERE, "Error in receive thread", e);
                    scheduleReconnect();
                    break; // 현재 스레드 종료
                }
            }
        }
        LOGGER.info("🛑 Receive thread stopped for client " + clientKey);
    }

    /**
     * Schedule a reconnection attempt with exponential backoff
     */
    private void scheduleReconnect() {
        synchronized (reconnectLock) {
            // 명시적으로 초기화 후 다시 설정
            isReconnecting = false;
            if (isDisposed) {
                return;
            }
            isReconnecting = true;

            // 이미 진행 중인 재연결 작업 취소
            if (reconnectFuture != null && !reconnectFuture.isDone()) {
                reconnectFuture.cancel(false);
            }

            // 지수 백오프로 재연결 지연 계산
            reconnectAttempts++;
            if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                LOGGER.warning("Maximum reconnection attempts reached (" + MAX_RECONNECT_ATTEMPTS + ")");
                isReconnecting = false;
                return;
            }

            // 지수 백오프 적용 (최대 30초)
            long delay = Math.min((long)Math.pow(2, reconnectAttempts) * 1000, MAX_RECONNECT_DELAY_MS);
            LOGGER.info("Scheduling reconnect attempt " + reconnectAttempts +
                    " of " + MAX_RECONNECT_ATTEMPTS + " in " + delay + "ms");

            reconnectFuture = heartbeatExecutor.schedule(() -> {
                try {
                    LOGGER.info("Executing scheduled reconnect attempt " + reconnectAttempts);
                    connect().thenAccept(connected -> {
                        if (connected) {
                            LOGGER.info("Reconnected successfully, resuming operations");
                            // 재연결 후 필요한 초기화 작업
                            sendInitConnect(RwcVaEnums.ConnectType.LIVE);
                        } else {
                            // 재연결 실패 시 다시 시도
                            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                                scheduleReconnect();
                            }
                        }
                    });
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error during reconnect", e);
                    synchronized (reconnectLock) {
                        isReconnecting = false;
                    }
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Process a received message
     */
    private void processMessage(byte command, byte subCommand, byte[] bodyData) {
        RwcVaEnums.Command cmd;
        try {
            cmd = RwcVaEnums.Command.fromValue(command);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Unknown command value: " + command);
            return;
        }

        switch (cmd) {
            case INIT_CLIENT:
                processInitClientCommand(subCommand, bodyData);
                break;
            case LIVE:
                processLiveCommand(subCommand, bodyData);
                break;
            case PLAYBACK:
                processPlaybackCommand(subCommand, bodyData);
                break;
            case LIVE_SIGNAL:
                // Just a keep-alive, nothing to process
                break;
            default:
                LOGGER.warning("Unknown command: " + command);
                break;
        }
    }

    boolean _flag = true;
    /**
     * Process INIT_CLIENT command
     */
    private void processInitClientCommand(byte subCommand, byte[] bodyData) {
        RwcVaEnums.InitClientSubCommand subCmd;
        try {
            subCmd = RwcVaEnums.InitClientSubCommand.fromValue(subCommand);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Unknown init client subcommand: " + subCommand);
            return;
        }

        switch (subCmd) {
            case ACCEPT:
                // Parse VA_INITCLIENT_ACCEPT
                if (bodyData != null) {
                    ByteBuffer buffer = ByteBuffer.wrap(bodyData);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);

                    int connectType = buffer.getInt();
                    long clientKey = buffer.getLong();
                    int version = buffer.getInt();

                    // 원래 clientKey 값 저장
                    this.serverClientKey = clientKey;

                    // 중요: 원본 바이트 배열 형태의 clientKey 저장
                    this.rawServerClientKey = new byte[8];
                    ByteBuffer keyBuffer = ByteBuffer.wrap(this.rawServerClientKey);
                    keyBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    keyBuffer.putLong(clientKey);

                    this.vaVersion = RwcVaEnums.Version.fromValue(version);


                    LOGGER.info("Connection accepted, client key (long): " + clientKey);
                    LOGGER.info("Connection accepted, raw client key bytes: " + bytesToHex(this.rawServerClientKey));

                    // 초기화 성공 콜백 호출
                    for (Consumer<Boolean> handler : initResponseHandlers) {
                        try {
                            handler.accept(true);
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Error in init response handler", e);
                        }
                    }
                }
                break;
            case DECLINE:
                // Handle connection decline
                LOGGER.warning("Connection declined by server");

                // 초기화 실패 콜백 호출
                for (Consumer<Boolean> handler : initResponseHandlers) {
                    try {
                        handler.accept(false);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error in init response handler", e);
                    }
                }

                disconnect();
                break;
            default:
                LOGGER.warning("Unknown init client subcommand: " + subCommand);
                break;
        }
    }

    /**
     * Process LIVE command with improved logging
     */
    private void processLiveCommand(byte subCommand, byte[] bodyData) {
        RwcVaEnums.LiveSubCommand subCmd;
        try {
            subCmd = RwcVaEnums.LiveSubCommand.fromValue(subCommand);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Unknown live subcommand: " + subCommand);
            return;
        }

        LOGGER.info("📡 Received LIVE command with subCommand: " + subCmd.name());

        switch (subCmd) {
            case LIVE_RESPONSE:
                LOGGER.info("✅ Live response received - server acknowledged the request");
                break;
            case LIVE_DATA_PREPARE:
                LOGGER.info("📦 Received LIVE_DATA_PREPARE - frame data reception starting");
                liveDataBuffer.clear();
                if (bodyData != null) {
                    liveDataBuffer.add(bodyData);
                }
                break;
            case LIVE_DATA:
                LOGGER.info("📊 Received LIVE_DATA chunk - size: " +
                        (bodyData != null ? bodyData.length : 0) + " bytes");
                if (bodyData != null) {
                    liveDataBuffer.add(bodyData);
                }
                break;
            case LIVE_DATA_COMPLETE:
                LOGGER.info("✅ Received LIVE_DATA_COMPLETE - processing complete frame");
                processCompleteLiveData();
                break;
            case INTERNAL_ERROR:
                handleInternalError(bodyData);
                break;
            default:
                LOGGER.warning("Unknown live subcommand: " + subCommand);
                break;
        }
    }

    /**
     * Process PLAYBACK command
     */
    private void processPlaybackCommand(byte subCommand, byte[] bodyData) {
        RwcVaEnums.PlaybackSubCommand subCmd;
        try {
            subCmd = RwcVaEnums.PlaybackSubCommand.fromValue(subCommand);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Unknown playback subcommand: " + subCommand);
            return;
        }

        switch (subCmd) {
            case PLAYBACK_RESPONSE:
                LOGGER.info("Playback response received");
                break;
            case PLAY_DATA_PREPARE:
                playbackDataBuffer.clear();
                if (bodyData != null) {
                    playbackDataBuffer.add(bodyData);
                }
                break;
            case PLAY_DATA:
                if (bodyData != null) {
                    playbackDataBuffer.add(bodyData);
                }
                break;
            case PLAY_DATA_COMPLETE:
                // Process complete playback data
                processCompletePlaybackData();
                break;
            case INTERNAL_ERROR:
                handleInternalError(bodyData);
                break;
            default:
                LOGGER.warning("Unknown playback subcommand: " + subCommand);
                break;
        }
    }

    /**
     * Process complete live data buffer with additional validation
     */
    private void processCompleteLiveData() {
        try {
            // 버퍼가 비어있는지 확인
            if (liveDataBuffer.isEmpty()) {
                LOGGER.warning("Live data buffer is empty, cannot process");
                return;
            }

            // Combine all buffer parts
            int totalSize = 0;
            for (byte[] part : liveDataBuffer) {
                totalSize += part.length;
            }

            if (totalSize == 0) {
                LOGGER.warning("Combined buffer size is zero, cannot process");
                return;
            }

            LOGGER.info("🔄 Combining " + liveDataBuffer.size() + " buffer parts, total size: " + totalSize + " bytes");

            byte[] combined = new byte[totalSize];
            int offset = 0;
            for (byte[] part : liveDataBuffer) {
                System.arraycopy(part, 0, combined, offset, part.length);
                offset += part.length;
            }

            // Parse LiveDataInfo
            LOGGER.info("🔍 Parsing live data info from " + combined.length + " bytes");
            LiveDataInfo liveData = parseLiveDataInfo(combined);

            LOGGER.info("📹 Parsed LiveDataInfo: cameraId=" + liveData.getCameraId() +
                    ", timestamp=" + liveData.getTimestamp() +
                    ", dataSize=" + liveData.getDataSize() +
                    ", objectCount=" + liveData.getObjectCount() +
                    ", codec=" + liveData.getCodec());

            if (onLiveData != null) {
                LOGGER.info("🚀 Forwarding LiveDataInfo to WebSocket client");
                onLiveData.accept(liveData);
            } else {
                LOGGER.warning("⚠️ onLiveData callback is null, cannot forward data");
            }

            // Clear buffer
            liveDataBuffer.clear();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing live data", e);
            // 에러 발생시 버퍼 초기화하여 다음 프레임부터 정상 처리 가능하도록 함
            liveDataBuffer.clear();
        }
    }

    /**
     * Process complete playback data buffer
     */
    private void processCompletePlaybackData() {
        try {
            // Combine all buffer parts
            int totalSize = 0;
            for (byte[] part : playbackDataBuffer) {
                totalSize += part.length;
            }

            byte[] combined = new byte[totalSize];
            int offset = 0;
            for (byte[] part : playbackDataBuffer) {
                System.arraycopy(part, 0, combined, offset, part.length);
                offset += part.length;
            }

            // 추가 처리 로직 구현 필요
            LOGGER.info("Processed complete playback data: " + totalSize + " bytes");

            // Clear buffer
            playbackDataBuffer.clear();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing playback data", e);
        }
    }

    /**
     * Parse LiveDataInfo from byte array
     */
    private LiveDataInfo parseLiveDataInfo(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        LiveDataInfo info = new LiveDataInfo();

        info.setCameraId(buffer.getInt());
        info.setDataSize(buffer.getInt());
        info.setTime(buffer.getInt());
        info.setMillisec(buffer.getInt());
        info.setIntraCode(RwcVaEnums.IntraCode.fromValue(buffer.getInt()));
        info.setEventInfo(buffer.getLong());
        info.setCodec(RwcVaEnums.CodecID.fromValue(buffer.getInt()));
        info.setObjectCount(buffer.getInt());
        info.setFpsReceiveCount(buffer.get());
        info.setFpsUseCount(buffer.get());
        info.setFpsDetectCount(buffer.get());
        info.setCountType(RwcVaEnums.CountType.fromValue(buffer.get()));
        info.setCount(buffer.getShort());
        info.setExtraDataSize(buffer.getShort());

        // Parse objects
        for (int i = 0; i < info.getObjectCount(); i++) {
            ObjectInfo obj = new ObjectInfo();
            obj.setIndex(buffer.getInt());
            obj.setType(RwcVaEnums.ObjectType.fromValue(buffer.getShort()));
            obj.setDetectionScore(buffer.getFloat());
            obj.setClassScore(buffer.getFloat());
            obj.setX(buffer.getFloat());
            obj.setY(buffer.getFloat());
            obj.setWidth(buffer.getFloat());
            obj.setHeight(buffer.getFloat());

            // Read attributes
            int attributeCount = 4;
            for (int j = 0; j < attributeCount; j++) {
                obj.addAttribute(buffer.getShort());
            }

            info.addObject(obj);
        }

        // Read extra data
        if (info.getExtraDataSize() > 0) {
            byte[] extraData = new byte[info.getExtraDataSize()];
            buffer.get(extraData);
            info.setExtraData(extraData);
        }

        // Read video data
        int remainingBytes = buffer.remaining();
        if (remainingBytes > 0) {
            byte[] videoData = new byte[remainingBytes];
            buffer.get(videoData);
            info.setData(videoData);
        }

        return info;
    }

    /**
     * Handle internal error messages
     */
    private void handleInternalError(byte[] bodyData) {
        if (bodyData == null) {
            return;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(bodyData);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            int errorCode = buffer.getInt();

            if (onInternalError != null) {
                onInternalError.accept(new RuntimeException("Internal error: " + errorCode));
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error parsing internal error", e);
        }
    }

    /**
     * Send an init connect request
     */
    public boolean sendInitConnect(RwcVaEnums.ConnectType connectType) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(connectType.getValue());

            LOGGER.info("Sending init connect request, connect type: " + connectType);

            return sendMessage(
                    (byte) RwcVaEnums.Command.INIT_CLIENT.getValue(),
                    (byte) RwcVaEnums.InitClientSubCommand.CONNECT.getValue(),
                    buffer.array()
            );
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending init connect", e);
            return false;
        }
    }

    /**
     * Send a live info request
     */
    public boolean sendLiveInfo(List<Integer> cameraIds) {
        if (serverClientKey == 0) {
            LOGGER.warning("클라이언트 키가 설정되지 않았습니다. 먼저 연결을 초기화해야 합니다.");
            return false;
        }

        try {
            if (cameraIds == null || cameraIds.isEmpty()) {
                LOGGER.warning("카메라 ID가 제공되지 않았습니다.");
                return false;
            }

            // 상수 정의
            final int MAX_CAMERA_COUNT = 1024;
            final int CAMERA_ID_SIZE = 4;

            // 바디 데이터 구성
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();

            // 1. 카메라 개수 (4바이트, 리틀 엔디안)
            byte[] countBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(cameraIds.size())
                    .array();
            bodyBytes.write(countBytes);

            // 2. 카메라 ID 추가
            for (Integer id : cameraIds) {
                byte[] idBytes = ByteBuffer.allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(id)
                        .array();
                bodyBytes.write(idBytes);
            }

            // 3. 패딩 추가 (0 바이트로)
            int paddingSize = (MAX_CAMERA_COUNT - cameraIds.size()) * CAMERA_ID_SIZE;
            byte[] padding = new byte[paddingSize]; // 자동으로 0으로 초기화됨
            bodyBytes.write(padding);

            // 메시지 전송
            byte command = (byte) RwcVaEnums.Command.LIVE.getValue();
            byte subCommand = (byte) RwcVaEnums.LiveSubCommand.LIVE_INFO.getValue();

            // 완성된 바디 데이터
            byte[] bodyData = bodyBytes.toByteArray();

            // 헤더와 함께 메시지 전송
            return sendMessage(command, subCommand, bodyData);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "라이브 정보 전송 중 오류 발생", e);
            return false;
        }
    }

    /**
     * Send a message to the server with better error handling
     */
    private boolean sendMessage(byte command, byte subCommand, byte[] bodyData) {
        if (!isConnected || isDisposed) {
            LOGGER.warning("Cannot send message: not connected or disposed");
            return false;
        }

        try {
            // 소켓 확인
            if (socket == null || !socket.isConnected() || outputStream == null) {
                LOGGER.warning("Socket or output stream is null, cannot send message");
                scheduleReconnect();
                return false;
            }

            // 1) 헤더 생성
            RexMessageHeader header = new RexMessageHeader();
            header.setPrefix(RwcVaConstants.DEFAULT_PREFIX);

            // 계산: 총 패킷 크기 (24바이트 헤더 + (옵션)4 + bodyData.length)
            int totalSize = RwcVaConstants.HEAD; // 기본 24
            if (bodyData != null) {
                totalSize += 4 + bodyData.length; // body length(4) + body
            }

            header.setPacketSize((short) totalSize);
            header.setCommand(command);
            header.setSubCommand(subCommand);

            if (rawServerClientKey != null) {
                header.setRawClientKey(rawServerClientKey);
            } else {
                header.setClientKey(serverClientKey);
            }

            header.setReserve(0);

            // 헤더 직렬화
            byte[] headerBytes = header.toBytes();
            LOGGER.info("Sending header only (hex): " + bytesToHex(headerBytes));

            // 2) Body length(4바이트) + Body
            byte[] sizeBytes = null;
            if (bodyData != null) {
                ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
                sizeBuffer.order(ByteOrder.LITTLE_ENDIAN);
                sizeBuffer.putInt(bodyData.length);
                sizeBytes = sizeBuffer.array();
                LOGGER.info("Body length (4 bytes): " + bytesToHex(sizeBytes)
                        + " decimal=" + bodyData.length);
            }

            // 3) 최종 전송할 패킷 통째로 합치기
            //    (headerBytes) + (sizeBytes + bodyData if any)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(headerBytes);

            if (sizeBytes != null) {
                baos.write(sizeBytes);
            }
            if (bodyData != null) {
                baos.write(bodyData);
            }

            byte[] finalPacket = baos.toByteArray();

            // 4) 최종 바이트 배열을 통째로 헥사덤프로 로깅
            LOGGER.info("Final packet (full hex dump), size=" + finalPacket.length + ": "
                    + bytesToHex(finalPacket));

            // 추가된 디버깅 코드: 보기 좋게 포맷팅된 hexdump 출력
            StringBuilder formattedHexDump = new StringBuilder();
            formattedHexDump.append("==== PACKET HEX DUMP (").append(finalPacket.length).append(" bytes) ====\n");

            for (int i = 0; i < finalPacket.length; i += 16) {
                formattedHexDump.append(String.format("%04X: ", i)); // 주소 오프셋

                // 16바이트씩 hex 값 출력
                for (int j = 0; j < 16; j++) {
                    if (i + j < finalPacket.length) {
                        formattedHexDump.append(String.format("%02X ", finalPacket[i + j]));
                    } else {
                        formattedHexDump.append("   "); // 공백 채우기
                    }
                    // 8바이트마다 추가 공백
                    if (j == 7) {
                        formattedHexDump.append(" ");
                    }
                }

                // ASCII 표현 추가
                formattedHexDump.append(" | ");
                for (int j = 0; j < 16; j++) {
                    if (i + j < finalPacket.length) {
                        byte b = finalPacket[i + j];
                        // 출력 가능한 ASCII 문자만 표시, 나머지는 '.'으로 표시
                        char c = (b >= 32 && b < 127) ? (char)b : '.';
                        formattedHexDump.append(c);
                    }
                }
                formattedHexDump.append("\n");
            }
            formattedHexDump.append("==== END OF HEX DUMP ====");

            LOGGER.info(formattedHexDump.toString());

            // 5) 소켓에 write
            outputStream.write(finalPacket);
            outputStream.flush();

            LOGGER.info("Message sent successfully: command=" + command +
                    ", subCommand=" + subCommand +
                    ", totalSize=" + totalSize + " bytes");
            return true;

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending message: " + e.getMessage(), e);

            // 연결 끊김 상태로 간주하고 재연결 예약
            isConnected = false;
            scheduleReconnect();
            return false;
        }
    }


    /**
     * Read exactly the specified number of bytes
     */
    private int readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        LOGGER.info("Attempting to read " + length + " bytes");

        int totalBytesRead = 0;

        while (totalBytesRead < length) {
            try {
                int bytesRead = in.read(buffer, offset + totalBytesRead, length - totalBytesRead);
                if (bytesRead == -1) {
                    if (totalBytesRead == 0) {
                        LOGGER.warning("End of stream reached with no data read");
                        return -1;
                    }
                    break; // Some data was read, so return that
                }
                totalBytesRead += bytesRead;
                LOGGER.info("Read " + bytesRead + " bytes. Total: " + totalBytesRead + "/" + length);
            } catch (SocketTimeoutException e) {
                // If we have read some data, return what we've got, otherwise rethrow
                if (totalBytesRead > 0) {
                    LOGGER.warning("Socket timeout after reading " + totalBytesRead + " bytes");
                    break;
                }
                throw e;
            }
        }

        LOGGER.info("Finished reading. Total bytes read: " + totalBytesRead + " out of expected " + length);
        return totalBytesRead;
    }

    // Utility methods
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    // Initialize response handler methods
    public void setInitResponseHandler(Consumer<Boolean> handler) {
        initResponseHandlers.add(handler);
    }

    public void removeInitResponseHandler(Consumer<Boolean> handler) {
        initResponseHandlers.remove(handler);
    }

    // Event handler setters
    public void setOnInternalError(Consumer<Throwable> handler) {
        this.onInternalError = handler;
    }

    public void setOnConnectChange(Consumer<Boolean> handler) {
        this.onConnectChange = handler;
    }

    public void setOnLiveData(Consumer<LiveDataInfo> handler) {
        this.onLiveData = handler;
    }

    // Getters
    public boolean isConnected() {
        return isConnected;
    }

    public String getServerIp() {
        return serverIp;
    }

    public int getServerPort() {
        return serverPort;
    }

    public RwcVaEnums.Version getVaVersion() {
        return vaVersion;
    }

    public long getServerClientKey() {
        return serverClientKey;
    }

    // 진단 정보 메서드
    public String getReceiveStatus() {
        return "클라이언트 정보: " + clientKey + " -> " + serverIp + ":" + serverPort +
                "\n연결 상태: " + (isConnected ? "연결됨" : "연결 안됨") +
                "\n서버 클라이언트 키: " + serverClientKey +
                "\n총 수신 메시지: " + totalMessagesReceived +
                "\n마지막 데이터 수신: " + (lastDataReceivedTime > 0 ?
                new java.util.Date(lastDataReceivedTime) : "없음");
    }
}