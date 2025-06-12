package com.assettrack.iot.network;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
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

    private final SessionManager sessionManager;
    private final ProtocolDetector protocolDetector;
    private final TeltonikaHandler teltonikaHandler;
    protected final Gt06Handler gt06Handler;
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

        logger.info("TrackerPipelineFactory constructed. GT06 Handler {}available",
                gt06Handler != null ? "" : "not ");
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // 1. Raw inbound logging
        if (pipeline.context("rawLogger") == null) {
            pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO));
        }

        // 2. Frame decoder: split on CRLF (0x0D 0x0A)
        if (pipeline.context("frameDecoder") == null) {
            pipeline.addLast("frameDecoder", new DelimiterBasedFrameDecoder(
                            512,
                            false,  // retain CRLF so protocol detector sees full frame length
                            Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                    )
            );
        }

        // 3. Protocol detection handler
        ProtocolDetectionHandler protocolDetectionHandler = new ProtocolDetectionHandler(protocolDetector);
        if (pipeline.context("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", protocolDetectionHandler);
            logger.info("Added ProtocolDetectionHandler for channel {}", channel.id());
        }

        // 4. Idle state handler
        if (pipeline.context("idleHandler") == null) {
            pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));
        }

        // 5. Unified decoder
        GenericProtocolDecoder genericDecoder = new GenericProtocolDecoder(
                sessionManager, protocolDetector, teltonikaHandler, gt06Handler
        );
        if (pipeline.context("decoder") == null) {
            pipeline.addLast("decoder", genericDecoder);
            logger.info("Added GenericProtocolDecoder for channel {}", channel.id());
        }

        // 6. Business logic handler
        NetworkMessageHandler networkMessageHandler = new NetworkMessageHandler(sessionManager, cacheManager);
        if (pipeline.context("messageHandler") == null) {
            pipeline.addLast("messageHandler", networkMessageHandler);
            logger.info("Added NetworkMessageHandler for channel {}", channel.id());
        }

        // 7. Exception & cleanup
        if (pipeline.context("exceptionHandler") == null) {
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
