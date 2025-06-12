package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.SessionManager;
import io.netty.buffer.ByteBuf; // Keep imports relevant to BaseProtocolDecoder if used there
import io.netty.buffer.Unpooled; // Keep imports relevant to BaseProtocolDecoder if used there
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.codec.binary.Hex; // Keep imports relevant to BaseProtocolDecoder if used there
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer; // Keep imports relevant to BaseProtocolDecoder if used there
import java.nio.ByteOrder; // Keep imports relevant to BaseProtocolDecoder if used there
import java.nio.charset.StandardCharsets; // Keep imports relevant to BaseProtocolDecoder if used there
import java.time.LocalDateTime; // Keep imports relevant to BaseProtocolDecoder if used there

@Component
@ChannelHandler.Sharable
public class GenericProtocolDecoder extends BaseProtocolDecoder {
    private static final Logger logger = LoggerFactory.getLogger(GenericProtocolDecoder.class);

    @Autowired
    public GenericProtocolDecoder(SessionManager sessionManager,
                                  ProtocolDetector protocolDetector,
                                  @Autowired(required = false) TeltonikaHandler teltonikaHandler,
                                  @Autowired(required = false) Gt06Handler gt06Handler) {
        super(sessionManager, protocolDetector, teltonikaHandler, gt06Handler);
    }

    // The GT06 parsing logic previously here has been moved to Gt06Handler.java.
    // This GenericProtocolDecoder now primarily acts as the concrete implementation
    // of BaseProtocolDecoder, which handles protocol routing to the appropriate ProtocolHandlers.

    // No need for generateGt06Response method here, as GT06Handler will handle its own responses.
}