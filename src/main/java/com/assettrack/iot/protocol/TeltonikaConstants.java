package com.assettrack.iot.protocol;

import java.util.Map;

public class TeltonikaConstants {
    public static final Map<Integer, String> CODECS = Map.of(
            0x07, "GH3000",
            0x08, "CODEC8",
            0x8E, "CODEC8EXT",
            0x0C, "CODEC12",
            0x0D, "CODEC13",
            0x10, "CODEC16"
    );

    public static final int IMEI_MIN_LENGTH = 15;
    public static final int IMEI_MAX_LENGTH = 17;
    public static final int HEADER_SIZE = 8;
    public static final int MAX_PACKET_SIZE = 10000;
}
