package com.assettrack.iot.network;

import com.assettrack.iot.config.AppConfig;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.ProtocolDetectionHandler;
import com.assettrack.iot.protocol.ProtocolDetector;
import com.assettrack.iot.protocol.TeltonikaConstants;
import com.assettrack.iot.protocol.TeltonikaHandler;
import com.assettrack.iot.protocol.GenericProtocolDecoder;
import com.assettrack.iot.protocol.Gt06Handler;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TrackerPipelineFactory extends ChannelInitializer<Channel> {

    private static final Logger logger = LoggerFactory.getLogger(TrackerPipelineFactory.class);

    /**
     * Maximum allowed Teltonika payload length (bytes).
     * Any packet declaring a larger length is dropped.
     */
    public static final int MAX_DATA_LENGTH = 1024 * 1024;

    private final SessionManager sessionManager;
    private final ProtocolDetector protocolDetector;
    private final TeltonikaHandler teltonikaHandler;
    private final Gt06Handler gt06Handler;
    private final CacheManager cacheManager;

    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetector protocolDetector,
            SessionManager sessionManager,
            CacheManager cacheManager,
            @Autowired(required = false) TeltonikaHandler teltonikaHandler,
            @Autowired(required = false) Gt06Handler gt06Handler) {

        this.protocolDetector = protocolDetector;
        this.sessionManager = sessionManager;
        this.cacheManager = cacheManager;
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler = gt06Handler;

        logger.info("TrackerPipelineFactory constructed. GT06 handler {}available",
                gt06Handler != null ? "" : "not ");
    }

    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // 1) Raw inbound logging
        if (pipeline.get("rawLogger") == null) {
            pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO));
        }

        // 2) CRLF-based frame split
        if (pipeline.get("frameDecoder") == null) {
            pipeline.addLast("frameDecoder", new DelimiterBasedFrameDecoder(
                    512,
                    false,
                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
            ));
        }

        // 2a) Teltonika LengthField decoder (only if handler present)
        if (teltonikaHandler != null && pipeline.get("teltonikaFrameDecoder") == null) {
            pipeline.addLast("teltonikaFrameDecoder", new LengthFieldBasedFrameDecoder(
                    MAX_DATA_LENGTH + TeltonikaConstants.HEADER_SIZE,
                    /* lengthFieldOffset= */ 4,
                    /* lengthFieldLength= */ 4,
                    /* lengthAdjustment= */ 0,
                    /* initialBytesToStrip= */ 0
            ));
            logger.info("Added Teltonika LengthFieldBasedFrameDecoder (maxFrameLength={})",
                    MAX_DATA_LENGTH + TeltonikaConstants.HEADER_SIZE);
        }

        // 3) Protocol detection
        if (pipeline.get("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", new ProtocolDetectionHandler(protocolDetector));
            logger.info("Added ProtocolDetectionHandler for channel {}", ch.id());
        }

        // 4) Idle timeout
        if (pipeline.get("idleHandler") == null) {
            pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));
        }

        // 5) Generic decoding & dispatch
        if (pipeline.get("decoder") == null) {
            pipeline.addLast("decoder",
                    new GenericProtocolDecoder(sessionManager, protocolDetector, teltonikaHandler, gt06Handler));
            logger.info("Added GenericProtocolDecoder for channel {}", ch.id());
        }

        // 6) Business logic handler
        if (pipeline.get("messageHandler") == null) {
            pipeline.addLast("messageHandler", new NetworkMessageHandler(sessionManager, cacheManager));
            logger.info("Added NetworkMessageHandler for channel {}", ch.id());
        }
    }
}
