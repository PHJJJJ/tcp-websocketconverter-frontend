package rexgen.videoproxy.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class RexMessageHeader {
    private static final Logger LOGGER = Logger.getLogger(RexMessageHeader.class.getName());

    private String prefix = RwcVaConstants.DEFAULT_PREFIX;
    private short packetSize;
    private byte command;
    private byte subCommand;
    private long clientKey;
    private byte[] rawClientKey = null;
    private long reserve;  // ulong -> long으로 수정

    public void setRawClientKey(byte[] rawClientKey) {
        this.rawClientKey = rawClientKey;
        // 로깅
        StringBuilder sb = new StringBuilder();
        for (byte b : rawClientKey) {
            sb.append(String.format("%02X ", b));
        }
        LOGGER.info("Setting raw client key bytes: " + sb.toString());
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(24); // 헤더 크기
        buffer.order(ByteOrder.LITTLE_ENDIAN); // 리틀 엔디안 설정

        // prefix 문자열을 바이트로 변환
        byte[] prefixBytes = prefix.getBytes();
        buffer.put(prefixBytes);

        // 나머지 필드 설정
        buffer.putShort(packetSize);
        buffer.put(command);
        buffer.put(subCommand);

        // 중요: 원본 바이트 배열이 있으면 그대로 사용, 없으면 일반 clientKey 사용
        if (rawClientKey != null) {
            buffer.put(rawClientKey);
        } else {
            buffer.putLong(clientKey);
        }

        buffer.putLong(reserve);

        return buffer.array();
    }

    public static RexMessageHeader fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        RexMessageHeader header = new RexMessageHeader();

        byte[] prefixBytes = new byte[RwcVaConstants.PREFIX];
        buffer.get(prefixBytes);
        header.setPrefix(new String(prefixBytes, StandardCharsets.US_ASCII));

        header.setPacketSize(buffer.getShort());
        header.setCommand(buffer.get());
        header.setSubCommand(buffer.get());
        header.setClientKey(buffer.getLong());
        header.setReserve(buffer.getLong());  // long으로 읽기

        return header;
    }

    // Getters and setters
    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public short getPacketSize() {
        return packetSize;
    }

    public void setPacketSize(short packetSize) {
        this.packetSize = packetSize;
    }

    public byte getCommand() {
        return command;
    }

    public void setCommand(byte command) {
        this.command = command;
    }

    public byte getSubCommand() {
        return subCommand;
    }

    public void setSubCommand(byte subCommand) {
        this.subCommand = subCommand;
    }

    public long getClientKey() {
        return clientKey;
    }

    public void setClientKey(long clientKey) {
        this.clientKey = clientKey;
        LOGGER.info("Setting client key to: " + clientKey);

        // 디버깅을 위해 16진수 표현도 추가
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(clientKey);
        byte[] bytes = buf.array();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        LOGGER.info("ClientKey bytes: " + sb.toString());
    }

    public long getReserve() {
        return reserve;
    }

    public void setReserve(long reserve) {  // long으로 변경
        this.reserve = reserve;
    }
}