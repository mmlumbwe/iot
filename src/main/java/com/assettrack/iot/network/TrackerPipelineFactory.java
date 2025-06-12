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

        // 2. Teltonika length-based framing (IMEI and AVL packets)
        if (teltonikaHandler != null && pipeline.get("teltonikaShortFrame") == null) {
            // IMEI packets: 2-byte length, followed by that many bytes
            pipeline.addLast("teltonikaShortFrame", new LengthFieldBasedFrameDecoder(
                    64,        // max IMEI length
                    0,         // length field offset
                    2,         // length field length
                    0,         // length adjustment
                    2,         // strip length field
                    true       // fail fast
            ));
            // AVL data: skip 4-byte preamble, then 4-byte length
            pipeline.addLast("teltonikaAvlFrame", new LengthFieldBasedFrameDecoder(
                    1024 * 1024, // max AVL packet size
                    4,           // skip preamble
                    4,           // length field length
                    0,           // length adjustment
                    8,           // strip preamble + length field
                    true
            ));
        }

        // 3. GT06 and TK103 CRLF-based framing
        if (gt06Handler != null && pipeline.get("gt06Tk103Frame") == null) {
            pipeline.addLast("gt06Tk103Frame", new DelimiterBasedFrameDecoder(
                    1024,                   // max frame length
                    true,                   // strip delimiter
                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
            ));
        }

        // 4. Protocol detection
        if (pipeline.get("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", new ProtocolDetectionHandler(protocolDetector));
            logger.info("Added ProtocolDetectionHandler for channel {}", channel.id());
        }

        // 5. Idle state handler
        if (pipeline.get("idleHandler") == null) {
            pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));
        }

        // 6. Unified protocol decoder and handler chaining
        if (pipeline.get("decoder") == null) {
            pipeline.addLast("decoder", new GenericProtocolDecoder(
                    sessionManager, protocolDetector, teltonikaHandler, gt06Handler
            ));
            logger.info("Added GenericProtocolDecoder for channel {}", channel.id());
        }

        // 7. Business logic
        if (pipeline.get("messageHandler") == null) {
            pipeline.addLast("messageHandler", new NetworkMessageHandler(sessionManager, cacheManager));
            logger.info("Added NetworkMessageHandler for channel {}", channel.id());
        }

        // 8. Exception & cleanup
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
