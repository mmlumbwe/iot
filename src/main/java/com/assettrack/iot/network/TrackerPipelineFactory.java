package com.assettrack.iot.network;

import com.assettrack.iot.protocol.*;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pipeline factory that defers framing to ProtocolDetectionHandler, which inspects
 * Teltonika, GT06 and TK103 packets and dynamically configures framing accordingly.
 */
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
        logger.info("TrackerPipelineFactory constructed. Handlers - Teltonika: {}available, GT06: {}available",
                teltonikaHandler != null ? "" : "not ",
                gt06Handler != null ? "" : "not ");
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // 1. Raw inbound logging for diagnostics
        pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO));

        // 2. Protocol detection and dynamic framing insertion
        pipeline.addLast("protocolDetector", new ProtocolDetectionHandler());
        logger.info("Added ProtocolDetectionHandler for channel {}", channel.id());

        // 3. Idle timeout monitoring
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // 4. Common decoding and business logic handlers
        pipeline.addLast("decoder", new GenericProtocolDecoder(
                sessionManager, protocolDetector, teltonikaHandler, gt06Handler));
        logger.info("Added GenericProtocolDecoder for channel {}", channel.id());

        pipeline.addLast("messageHandler", new NetworkMessageHandler(sessionManager, cacheManager));
        logger.info("Added NetworkMessageHandler for channel {}", channel.id());

        // 5. Cleanup and exception handling
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
