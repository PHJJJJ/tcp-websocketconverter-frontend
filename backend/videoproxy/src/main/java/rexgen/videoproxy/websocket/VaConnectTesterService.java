package rexgen.videoproxy.websocket;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import rexgen.videoproxy.protocol.RexMessageHeader;
import rexgen.videoproxy.protocol.RwcVaConstants;
import rexgen.videoproxy.protocol.RwcVaEnums;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 개선된 TCP 클라이언트 - 자동 재연결 로직 추가
 */
//@Component
public class VaConnectTesterService {
    private static final Logger LOGGER = Logger.getLogger(VaConnectTesterService.class.getName());

    private final String serverIp;
    private final int serverPort;

    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private Thread receiveThread;
    private volatile boolean running = false;
    private volatile boolean isDisposed = false;

    // 서버가 InitClientAccept 시 내려주는 clientKey/버전 등
    private long serverClientKey = 0;
    private RwcVaEnums.Version vaVersion = RwcVaEnums.Version.v40;

    public VaConnectTesterService() {
        this.serverIp = "172.20.0.26";
        this.serverPort = 6990;
    }

    @PostConstruct
    public void init() {
        LOGGER.info("[PostConstruct] Starting VaConnectTesterService initialization...");
        this.running = true;
        this.isDisposed = false;

        try {
            connect();
            sendInitConnect(RwcVaEnums.ConnectType.LIVE);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[PostConstruct] Error: Failed to connect or send InitConnect", e);
        }
    }

    /**
     * 소켓 연결 후, 별도 스레드에서 메시지 수신 대기
     */
    public synchronized void connect() throws IOException {
        // 이미 연결되어 있다면 닫기
        closeSocket();

        if (isDisposed) {
            LOGGER.info("[connect] Service is disposed, not reconnecting.");
            return;
        }

        LOGGER.info(String.format("[connect] Attempting to connect to %s:%d", serverIp, serverPort));

        try {
            this.socket = new Socket(serverIp, serverPort);

            // 타임아웃 없이 설정 (무한 대기)
            socket.setSoTimeout(0);       // 타임아웃 없음 (무한 대기)
            socket.setKeepAlive(false);   // KeepAlive 사용 안함
            socket.setTcpNoDelay(false);  // Nagle 알고리즘 사용

            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();

            LOGGER.info("[connect] Socket connected successfully.");

            // 스레드가 실행 중이 아니라면 새로 시작
            if (receiveThread == null || !receiveThread.isAlive()) {
                this.receiveThread = new Thread(this::receiveLoop, "SimpleVaTcpClient-ReceiveThread");
                this.receiveThread.start();
                LOGGER.info("[connect] Receive thread started.");
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[connect] Failed to connect: " + e.getMessage(), e);
            // 연결 실패 시 일정 시간 후 재시도
            scheduleReconnect();
            throw e;
        }
    }

    /**
     * 일정 시간 후 재연결 시도
     */
    private void scheduleReconnect() {
        if (isDisposed) return;

        LOGGER.info("[scheduleReconnect] Scheduling reconnection in 5 seconds...");
        new Thread(() -> {
            try {
                Thread.sleep(5000); // 5초 대기
                if (!isDisposed && running) {
                    LOGGER.info("[scheduleReconnect] Attempting reconnection...");
                    try {
                        connect();
                        sendInitConnect(RwcVaEnums.ConnectType.LIVE);
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "[scheduleReconnect] Reconnection failed", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "[scheduleReconnect] Reconnection thread interrupted", e);
            }
        }).start();
    }

    /**
     * 소켓만 닫기 (스레드는 유지)
     */
    private void closeSocket() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
                LOGGER.info("[closeSocket] Socket closed successfully.");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[closeSocket] Error closing socket", e);
            }
        }
    }

    /**
     * 소켓/스레드 모두 종료 (서비스 종료시)
     */
    public void close() {
        LOGGER.info("[close] Closing client (socket + thread)...");
        isDisposed = true;
        running = false;

        if (receiveThread != null && receiveThread.isAlive()) {
            receiveThread.interrupt();
        }

        closeSocket();
        LOGGER.info("[close] Client fully closed.");
    }

    /**
     * InitClientConnect 요청 보내기
     */
    public void sendInitConnect(RwcVaEnums.ConnectType connectType) throws IOException {
        LOGGER.info("[sendInitConnect] Preparing to send InitConnect packet, connectType=" + connectType);

        if (socket == null || socket.isClosed() || outputStream == null) {
            LOGGER.warning("[sendInitConnect] Socket is closed or output stream is null, reconnecting...");
            connect();
            if (socket == null || socket.isClosed()) {
                throw new IOException("Failed to reconnect for sending InitConnect");
            }
        }

        try {
            // Body: 4바이트 (connectType)
            ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(connectType.getValue());
            byte[] bodyData = buffer.array();

            // 헤더 생성
            RexMessageHeader header = new RexMessageHeader();
            header.setPrefix(RwcVaConstants.DEFAULT_PREFIX);
            // packetSize = 24(기본 헤더) + 4(Body Length) + bodyData.length
            short packetSize = (short) (RwcVaConstants.HEAD + 4 + bodyData.length);
            header.setPacketSize(packetSize);
            header.setCommand((byte) RwcVaEnums.Command.INIT_CLIENT.getValue());
            header.setSubCommand((byte) RwcVaEnums.InitClientSubCommand.CONNECT.getValue());
            // clientKey = 0 (초기) 또는 serverClientKey (재연결시)
            header.setClientKey(serverClientKey);
            header.setReserve(0);

            byte[] headerBytes = header.toBytes();
            LOGGER.info(String.format("[sendInitConnect] Header bytes (%d): %s",
                    headerBytes.length, bytesToHex(headerBytes)));

            // 전송 (헤더 + body length + body)
            ByteBuffer all = ByteBuffer.allocate(headerBytes.length + 4 + bodyData.length);
            all.put(headerBytes);
            all.putInt(bodyData.length); // body length(4바이트)
            all.put(bodyData);

            byte[] packet = all.array();
            LOGGER.info(String.format("[sendInitConnect] Final packet (size=%d): %s",
                    packet.length, bytesToHex(packet)));

            outputStream.write(packet);
            outputStream.flush();

            LOGGER.info("[sendInitConnect] Sent InitConnect packet successfully.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[sendInitConnect] Failed to send: " + e.getMessage(), e);
            // 전송 실패 시 재연결 시도
            scheduleReconnect();
            throw e;
        }
    }

    /**
     * 수신 루프: C# 코드와 유사하게 재연결 로직 추가
     */
    private void receiveLoop() {
        LOGGER.info("[receiveLoop] Started receive loop.");
        byte[] headBuffer = new byte[RwcVaConstants.HEAD]; // 24바이트
        byte[] bodySizeBuf = new byte[4];

        while (running && !isDisposed) {
            try {
                // 1) 헤더 24바이트 읽기
                Arrays.fill(headBuffer, (byte) 0);
                LOGGER.info("[receiveLoop] Attempting to read 24-byte header...");
                int read = readFully(inputStream, headBuffer, 0, headBuffer.length);
                if (read <= 0) {
                    LOGGER.warning("[receiveLoop] Failed to read header, reconnecting...");
                    reconnect();
                    continue;
                }

                // 2) 헤더 파싱
                LOGGER.info(String.format("[receiveLoop] Raw header bytes: %s", bytesToHex(headBuffer)));
                RexMessageHeader header = RexMessageHeader.fromBytes(headBuffer);
                LOGGER.info(String.format("[receiveLoop] Header parsed -> command=%d, subCommand=%d, packetSize=%d, clientKey=%d",
                        header.getCommand(), header.getSubCommand(), header.getPacketSize(), header.getClientKey()));

                // 3) Prefix 검증 (C# 코드에서 추가)
                if (header.getPrefix() != RwcVaConstants.DEFAULT_PREFIX) {
                    LOGGER.warning("[receiveLoop] Invalid prefix: " + header.getPrefix());
                    reconnect();
                    continue;
                }

                // 4) Body 사이즈 읽기
                int bodySize = 0;
                if (header.getPacketSize() > RwcVaConstants.HEAD) {
                    // 4바이트 body length
                    LOGGER.info("[receiveLoop] Reading body size (4 bytes)...");
                    read = readFully(inputStream, bodySizeBuf, 0, 4);
                    if (read < 4) {
                        LOGGER.warning("[receiveLoop] Failed to read body size, reconnecting...");
                        reconnect();
                        continue;
                    }
                    bodySize = ByteBuffer.wrap(bodySizeBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    LOGGER.info("[receiveLoop] Body size: " + bodySize);

                    // 5) 패킷 크기 유효성 검사 (C# 코드에서 추가)
                    if (header.getPacketSize() - RwcVaConstants.HEAD - 4 != bodySize) {
                        LOGGER.warning(String.format("[receiveLoop] Invalid packet size: header=%d, calculated=%d",
                                header.getPacketSize(), RwcVaConstants.HEAD + 4 + bodySize));
                        reconnect();
                        continue;
                    }
                }

                // 6) Body 데이터 읽기
                byte[] bodyBuf = null;
                if (bodySize > 0) {
                    bodyBuf = new byte[bodySize];
                    LOGGER.info(String.format("[receiveLoop] Reading %d bytes for body...", bodySize));
                    read = readFully(inputStream, bodyBuf, 0, bodySize);
                    if (read < bodySize) {
                        LOGGER.warning("[receiveLoop] Failed to read entire body, reconnecting...");
                        reconnect();
                        continue;
                    }
                    LOGGER.info(String.format("[receiveLoop] Body read -> %s", bytesToHex(bodyBuf)));
                }

                // 7) 명령어 별 처리 (C# 코드와 유사하게 구현)
                // Command 값 직접 비교 (상수 표현식 문제 해결)
                byte command = header.getCommand();
                if (command == (byte) RwcVaEnums.Command.INIT_CLIENT.getValue()) {
                    handleInitClientCommand(header.getSubCommand(), bodyBuf);
                } else {
                    LOGGER.warning("[receiveLoop] Unknown command: " + command);
                }
                // SocketTimeoutException은 타임아웃이 0일 때는 발생하지 않으므로 이 예외 처리는 유지할 필요가 있음
            } catch (SocketTimeoutException e) {
                // 타임아웃이 0으로 설정되어 있어도 유지 (향후 타임아웃 값 변경시 필요)
                LOGGER.log(Level.WARNING, "[receiveLoop] Socket read timeout, reconnecting...", e);
                reconnect();
            } catch (IOException e) {
                if (!isDisposed) {
                    LOGGER.log(Level.SEVERE, "[receiveLoop] I/O error, reconnecting...", e);
                    reconnect();
                }
            } catch (Exception e) {
                if (!isDisposed) {
                    LOGGER.log(Level.SEVERE, "[receiveLoop] Unexpected error", e);
                    reconnect();
                }
            }
        }

        LOGGER.info("[receiveLoop] Loop stopped.");
        // 스레드 종료 시에도 소켓이 열려있다면 닫기
        if (!isDisposed) {
            closeSocket();
        }
    }

    /**
     * 연결 재시도 (receiveLoop 내부에서 호출)
     */
    private void reconnect() {
        if (isDisposed) return;

        try {
            LOGGER.info("[reconnect] Attempting to reconnect...");
            connect();
            sendInitConnect(RwcVaEnums.ConnectType.LIVE);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[reconnect] Failed to reconnect", e);
            // 실패 시 지연 재시도 예약
            scheduleReconnect();
        }
    }

    /**
     * InitClient 명령어 처리
     */
    private void handleInitClientCommand(byte subCmd, byte[] bodyBuf) {
        LOGGER.info("[handleInitClientCommand] Handling InitClient command, subCmd=" + subCmd);

        if (subCmd == (byte) RwcVaEnums.InitClientSubCommand.ACCEPT.getValue()) {
            LOGGER.info("[handleInitClientCommand] SubCommand=ACCEPT -> parsing body...");
            parseInitClientAccept(bodyBuf);
        } else if (subCmd == (byte) RwcVaEnums.InitClientSubCommand.DECLINE.getValue()) {
            LOGGER.warning("[handleInitClientCommand] SubCommand=DECLINE -> server declined connection!");
        } else {
            LOGGER.info("[handleInitClientCommand] Server returned unknown subCmd: " + subCmd);
        }
    }

    /**
     * InitClientAccept body 파싱
     */
    private void parseInitClientAccept(byte[] body) {
        if (body == null || body.length < 12) {
            LOGGER.warning("[parseInitClientAccept] Invalid or empty body. length=" +
                    (body == null ? "null" : body.length));
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);

        int connectType = buf.getInt();
        long clientKey = buf.getLong();
        int version = 0;
        if (buf.remaining() >= 4) {
            version = buf.getInt();
        }

        this.serverClientKey = clientKey;
        this.vaVersion = RwcVaEnums.Version.fromValue(version);

        LOGGER.info(String.format("[parseInitClientAccept] Parsed -> connectType=%d, clientKey=%d, version=%d",
                connectType, clientKey, version));
    }

    /**
     * 바이트를 정확히 length만큼 읽는 메서드
     */
    private int readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        if (in == null) {
            LOGGER.severe("[readFully] Input stream is null");
            return -1;
        }

        LOGGER.info(String.format("[readFully] Attempting to read %d bytes...", length));
        int totalRead = 0;

        while (totalRead < length) {
            int read = in.read(buffer, offset + totalRead, length - totalRead);
            if (read == -1) {
                if (totalRead == 0) {
                    LOGGER.warning("[readFully] Stream ended (EOF) before any data was read.");
                    return -1;
                }
                LOGGER.warning("[readFully] Stream ended (EOF) after partial read: " + totalRead);
                break;
            }
            totalRead += read;
            LOGGER.info(String.format("[readFully] Read %d bytes, totalRead=%d/%d", read, totalRead, length));
        }
        if (totalRead < length) {
            LOGGER.warning(String.format("[readFully] Could not read the full %d bytes. Only got %d.", length, totalRead));
        } else {
            LOGGER.info(String.format("[readFully] Successfully read %d/%d bytes.", totalRead, length));
        }
        return totalRead;
    }

    /**
     * 간단한 헥사덤프 출력용
     */
    private String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "(null/empty)";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    public long getServerClientKey() {
        return serverClientKey;
    }

    public RwcVaEnums.Version getVaVersion() {
        return vaVersion;
    }
}