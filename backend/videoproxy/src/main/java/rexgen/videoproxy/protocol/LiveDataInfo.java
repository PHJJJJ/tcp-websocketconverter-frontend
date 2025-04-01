package rexgen.videoproxy.protocol;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LiveDataInfo {
    private int cameraId;
    private int dataSize;
    private int time;
    private int millisec;
    private RwcVaEnums.IntraCode intraCode;
    private long eventInfo;
    private RwcVaEnums.CodecID codec;
    private int objectCount;
    private byte fpsReceiveCount;
    private byte fpsUseCount;
    private byte fpsDetectCount;
    private RwcVaEnums.CountType countType;
    private short count;
    private short extraDataSize;
    private List<ObjectInfo> objects = new ArrayList<>();
    private byte[] extraData;
    private byte[] data;

    public Date getTimestamp() {
        // Convert time and millisec to Date
        long timestampMillis = ((long) time) * 1000 + millisec;
        return new Date(timestampMillis);
    }

    // Getters and setters
    public int getCameraId() {
        return cameraId;
    }

    public void setCameraId(int cameraId) {
        this.cameraId = cameraId;
    }

    public int getDataSize() {
        return dataSize;
    }

    public void setDataSize(int dataSize) {
        this.dataSize = dataSize;
    }

    public int getTime() {
        return time;
    }

    public void setTime(int time) {
        this.time = time;
    }

    public int getMillisec() {
        return millisec;
    }

    public void setMillisec(int millisec) {
        this.millisec = millisec;
    }

    public RwcVaEnums.IntraCode getIntraCode() {
        return intraCode;
    }

    public void setIntraCode(RwcVaEnums.IntraCode intraCode) {
        this.intraCode = intraCode;
    }

    public long getEventInfo() {
        return eventInfo;
    }

    public void setEventInfo(long eventInfo) {
        this.eventInfo = eventInfo;
    }

    public RwcVaEnums.CodecID getCodec() {
        return codec;
    }

    public void setCodec(RwcVaEnums.CodecID codec) {
        this.codec = codec;
    }

    public int getObjectCount() {
        return objectCount;
    }

    public void setObjectCount(int objectCount) {
        this.objectCount = objectCount;
    }

    public byte getFpsReceiveCount() {
        return fpsReceiveCount;
    }

    public void setFpsReceiveCount(byte fpsReceiveCount) {
        this.fpsReceiveCount = fpsReceiveCount;
    }

    public byte getFpsUseCount() {
        return fpsUseCount;
    }

    public void setFpsUseCount(byte fpsUseCount) {
        this.fpsUseCount = fpsUseCount;
    }

    public byte getFpsDetectCount() {
        return fpsDetectCount;
    }

    public void setFpsDetectCount(byte fpsDetectCount) {
        this.fpsDetectCount = fpsDetectCount;
    }

    public RwcVaEnums.CountType getCountType() {
        return countType;
    }

    public void setCountType(RwcVaEnums.CountType countType) {
        this.countType = countType;
    }

    public short getCount() {
        return count;
    }

    public void setCount(short count) {
        this.count = count;
    }

    public short getExtraDataSize() {
        return extraDataSize;
    }

    public void setExtraDataSize(short extraDataSize) {
        this.extraDataSize = extraDataSize;
    }

    public List<ObjectInfo> getObjects() {
        return objects;
    }

    public void setObjects(List<ObjectInfo> objects) {
        this.objects = objects;
    }

    public void addObject(ObjectInfo object) {
        this.objects.add(object);
    }

    public byte[] getExtraData() {
        return extraData;
    }

    public void setExtraData(byte[] extraData) {
        this.extraData = extraData;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
