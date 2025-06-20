package com.assettrack.iot.network;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.DynamicProtocolFramer; // Import the new handler
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder; // Ensure this is imported
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

        // 1. Raw inbound logging - should be first to see all bytes
        if (pipeline.get("rawLogger") == null) {
            pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO));
        }

        // REMOVED: The generic DelimiterBasedFrameDecoder, as framing is now dynamic
        // if (pipeline.context("frameDecoder") == null) {
        //     pipeline.addLast("frameDecoder", new DelimiterBasedFrameDecoder(
        //                     512,
        //                     false,
        //                     Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
        //             )
        //     );
        // }

        // 2. Protocol detection - must be before any protocol-specific frame decoders
        if (pipeline.get("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", new ProtocolDetectionHandler(protocolDetector, teltonikaHandler, gt06Handler));
            logger.info("Added ProtocolDetectionHandler for channel {}", channel.id());
        }

        // NEW: Handler to dynamically add the correct frame decoder based on detected protocol
        if (pipeline.get("dynamicFramer") == null) {
            pipeline.addLast("dynamicFramer", new DynamicProtocolFramer());
            logger.info("Added DynamicProtocolFramer for channel {}", channel.id());
        }

        // 3. Idle state handler (place after framing, so it monitors framed message traffic)
        if (pipeline.get("idleHandler") == null) {
            pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));
        }

        // 4. Unified protocol decoder and handler chaining (will now receive framed messages)
        if (pipeline.get("decoder") == null) {
            pipeline.addLast("decoder", new GenericProtocolDecoder(
                    sessionManager, protocolDetector, teltonikaHandler, gt06Handler
            ));
            logger.info("Added GenericProtocolDecoder for channel {}", channel.id());
        }

        // 5. Business logic
        if (pipeline.get("messageHandler") == null) {
            pipeline.addLast("messageHandler", new NetworkMessageHandler(sessionManager, cacheManager));
            logger.info("Added NetworkMessageHandler for channel {}", channel.id());
        }

        // 6. Exception & cleanup
        if (pipeline.get("exceptionHandler") == null) {
            pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    logger.error("Pipeline error for channel {}", ctx.channel().id(), cause);
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