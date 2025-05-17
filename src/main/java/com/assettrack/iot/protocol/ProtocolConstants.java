package com.assettrack.iot.protocol;

import io.netty.util.AttributeKey;

public class ProtocolConstants {
    public static final AttributeKey<String> PROTOCOL_ATTRIBUTE =
            AttributeKey.valueOf("protocolType");
}