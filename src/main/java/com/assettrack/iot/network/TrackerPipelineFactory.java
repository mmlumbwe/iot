package com.assettrack.iot.network;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
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

    private final ProtocolDetector protocolDetector;
    private final SessionManager sessionManager;
    private final CacheManager cacheManager;
    private final TeltonikaHandler teltonikaHandler;
    private final Gt06Handler gt06Handler;

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
        logger.info("TrackerPipelineFactory constructed. Teltonika handler {}available, GT06 handler {}available",
                teltonikaHandler != null ? "" : "not ",
                gt06Handler != null ? "" : "not ");
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // 1. Raw inbound logging
        if (pipeline.get("rawLogger") == null) {
            pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO));
        }

        // 2. Frame decoders
        // 2a. Teltonika: length-based frame decoder
        if (teltonikaHandler != null && pipeline.get("teltonikaFrameDecoder") == null) {
            pipeline.addLast("teltonikaFrameDecoder", new LengthFieldBasedFrameDecoder(
                    10_240,      // maxFrameLength
                    4,           // lengthFieldOffset (skip 4-byte preamble)
                    4,           // lengthFieldLength (4-byte data length)
                    0,           // lengthAdjustment
                    8            // initialBytesToStrip (preamble + length)
            ));
            logger.info("Added Teltonika LengthFieldBasedFrameDecoder for channel {}", channel.id());
        }

        // 2b. GT06: split on CRLF (0x0D 0x0A)
        if (gt06Handler != null && pipeline.get("gt06FrameDecoder") == null) {
            pipeline.addLast("gt06FrameDecoder", new DelimiterBasedFrameDecoder(
                    512,
                    false,  // retain CRLF
                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
            ));
            logger.info("Added GT06 DelimiterBasedFrameDecoder for channel {}", channel.id());
        }

        // 3. Protocol detection
        if (pipeline.get("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", new ProtocolDetectionHandler(protocolDetector));
            logger.info("Added ProtocolDetectionHandler for channel {}", channel.id());
        }

        // 4. Idle state handler
        if (pipeline.get("idleHandler") == null) {
            pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));
        }

        // 5. Unified protocol decoder
        if (pipeline.get("decoder") == null) {
            pipeline.addLast("decoder", new GenericProtocolDecoder(
                    sessionManager, protocolDetector, teltonikaHandler, gt06Handler
            ));
            logger.info("Added GenericProtocolDecoder for channel {}", channel.id());
        }

        // 6. Business logic
        if (pipeline.get("messageHandler") == null) {
            pipeline.addLast("messageHandler", new NetworkMessageHandler(sessionManager, cacheManager));
            logger.info("Added NetworkMessageHandler for channel {}", channel.id());
        }

        // 7. Exception & cleanup
        if (pipeline.get("exceptionHandler") == null) {
            pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    logger.error("Pipeline error", cause);
                    ctx.close();
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    sessionManager.removeSession(ctx.channel());
                    logger.info("Channel inactive, session removed: {}", ctx.channel().id());
                    super.channelInactive(ctx);
                }
            });
        }
    }
}
