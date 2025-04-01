package rexgen.videoproxy.protocol;

public class RwcVaEnums {
    public enum Version {
        v10(10),
        v20(20),
        v30(30),
        v40(40);

        private final int value;

        Version(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static Version fromValue(int value) {
            for (Version v : values()) {
                if (v.getValue() == value) {
                    return v;
                }
            }
            return v10; // Default to v10
        }
    }

    public enum ConnectType {
        LIVE(1),
        PLAYBACK(2);

        private final int value;

        ConnectType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum ErrorCode {
        NOT_ERROR(0);

        private final int value;

        ErrorCode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum Command {
        INIT_CLIENT(10),
        LIVE(11),
        PLAYBACK(12),
        LIVE_SIGNAL(100);

        private final int value;

        Command(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static Command fromValue(int value) {
            for (Command c : values()) {
                if (c.getValue() == value) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown command value: " + value);
        }
    }

    public enum InitClientSubCommand {
        CONNECT(1),
        ACCEPT(100),
        DECLINE(200);

        private final int value;

        InitClientSubCommand(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static InitClientSubCommand fromValue(int value) {
            for (InitClientSubCommand c : values()) {
                if (c.getValue() == value) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown init client subcommand value: " + value);
        }
    }

    public enum LiveSubCommand {
        LIVE_INFO(1),
        LIVE_DATA_PREPARE(10),
        LIVE_DATA(11),
        LIVE_DATA_COMPLETE(12),
        LIVE_RESPONSE(100),
        INTERNAL_ERROR(200);

        private final int value;

        LiveSubCommand(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static LiveSubCommand fromValue(int value) {
            for (LiveSubCommand c : values()) {
                if (c.getValue() == value) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown live subcommand value: " + value);
        }
    }

    public enum PlaybackSubCommand {
        PLAYBACK_INFO(1),
        RECORD_RANGE(2),
        TIMELINE_INFO(3),
        PLAY_CONTROL(4),
        PLAY_SEEK(5),
        PLAY_DATA_PREPARE(10),
        PLAY_DATA(11),
        PLAY_DATA_COMPLETE(12),
        TIMELINE_PREPARE(20),
        TIMELINE_DATA(21),
        TIMELINE_COMPLETE(22),
        PLAYBACK_RESPONSE(100),
        RANGE_RESPONSE(101),
        TIMELINE_RESPONSE(102),
        PLAY_CONTROL_RESPONSE(103),
        PLAY_SEEK_RESPONSE(104),
        PLAYBACK_DATA_END(150),
        INTERNAL_ERROR(200),
        NOT_EXIST_DATA(201);

        private final int value;

        PlaybackSubCommand(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static PlaybackSubCommand fromValue(int value) {
            for (PlaybackSubCommand c : values()) {
                if (c.getValue() == value) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown playback subcommand value: " + value);
        }
    }

    public enum IntraCode {
        UNKNOWN(0),
        INTRA(1),
        PREDICT(2),
        BIPREDICT(3);

        private final int value;

        IntraCode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static IntraCode fromValue(int value) {
            for (IntraCode c : values()) {
                if (c.getValue() == value) {
                    return c;
                }
            }
            return UNKNOWN;
        }
    }

    public enum CodecID {
        NONE(0),
        RAW(1),
        MJPEG(2),
        MPEG4(3),
        H264(4);

        private final int value;

        CodecID(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static CodecID fromValue(int value) {
            for (CodecID c : values()) {
                if (c.getValue() == value) {
                    return c;
                }
            }
            return NONE;
        }
    }

    public enum ControlType {
        UNKNOWN(0),
        PLAY(1),
        STOP(2),
        SPEED(3);

        private final int value;

        ControlType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum SpeedType {
        UNKNOWN(0),
        X1(1),
        X2(2),
        X4(3),
        X8(4),
        MAX(5);

        private final int value;

        SpeedType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum CountType {
        NOT_USED(0),
        CROWD(1),
        AREA_MOVEMENT(2);

        private final int value;

        CountType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static CountType fromValue(int value) {
            for (CountType c : values()) {
                if (c.getValue() == value) {
                    return c;
                }
            }
            return NOT_USED;
        }
    }

    // ObjectType enum would be very long, so I'll define the key ones
    public enum ObjectType {
        CAR(0),
        SUV(1),
        VAN(2),
        PERSON(31),
        FACE_FULL(32),
        FACE_SIDE(33);

        private final int value;

        ObjectType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static ObjectType fromValue(int value) {
            for (ObjectType t : values()) {
                if (t.getValue() == value) {
                    return t;
                }
            }
            return CAR; // Default
        }
    }
}
