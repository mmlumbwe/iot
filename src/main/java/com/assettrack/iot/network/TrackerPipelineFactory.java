package com.assettrack.iot.network;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager; // Import CacheManager
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
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
    private final CacheManager cacheManager; // Inject CacheManager

    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetector protocolDetector,
            SessionManager sessionManager,
            CacheManager cacheManager, // Add CacheManager to constructor
            @Autowired(required = false) TeltonikaHandler teltonikaHandler,
            @Autowired(required = false) Gt06Handler gt06Handler) {

        this.protocolDetector = protocolDetector;
        this.sessionManager = sessionManager;
        this.cacheManager = cacheManager; // Assign CacheManager
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler       = gt06Handler;

        logger.info("TrackerPipelineFactory constructed.");
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // Create a new instance of ProtocolDetectionHandler for each channel
        ProtocolDetectionHandler protocolDetectionHandler = new ProtocolDetectionHandler(protocolDetector);
        logger.info("Adding ProtocolDetectionHandler to pipeline — new instance created for channel ID: {}, instance ID: {}",
                channel.id(), System.identityHashCode(protocolDetectionHandler));

        // Create a new instance of GenericProtocolDecoder for each channel, passing its dependencies
        GenericProtocolDecoder genericDecoder = new GenericProtocolDecoder(sessionManager, protocolDetector, teltonikaHandler, gt06Handler);
        logger.info("Adding GenericProtocolDecoder to pipeline — new instance created for channel ID: {}, instance ID: {}",
                channel.id(), System.identityHashCode(genericDecoder));

        // Create a new instance of NetworkMessageHandler for each channel, passing its assumed dependencies
        NetworkMessageHandler networkMessageHandler = new NetworkMessageHandler(sessionManager, cacheManager); // Pass both dependencies
        logger.info("Adding NetworkMessageHandler to pipeline — new instance created for channel ID: {}, instance ID: {}",
                channel.id(), System.identityHashCode(networkMessageHandler));


        // 1. Raw inbound byte logging
        if (pipeline.context("rawLogger") == null) {
            pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO));
        }

        // 2. Protocol detection (GT06, Teltonika, etc.)
        if (pipeline.context("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", protocolDetectionHandler);
        }

        // 3. Connection idle detection
        if (pipeline.context("idleHandler") == null) {
            pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));
        }

        // 4. Unified decoder
        if (pipeline.context("decoder") == null) {
            pipeline.addLast("decoder", genericDecoder);
        }

        // 5. Business logic handler
        if (pipeline.context("messageHandler") == null) {
            pipeline.addLast("messageHandler", networkMessageHandler);
        }

        // 6. Exception & cleanup handler
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