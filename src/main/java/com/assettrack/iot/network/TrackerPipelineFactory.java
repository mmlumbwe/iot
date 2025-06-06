package com.assettrack.iot.network;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.GenericProtocolDecoder;
import com.assettrack.iot.protocol.ProtocolDetectionHandler;
import com.assettrack.iot.session.SessionManager;
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

    private final ProtocolDetectionHandler protocolDetectionHandler;
    private final GenericProtocolDecoder genericDecoder;
    private final NetworkMessageHandler networkMessageHandler;
    private final SessionManager sessionManager;

    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetectionHandler protocolDetectionHandler,
            GenericProtocolDecoder genericDecoder,
            NetworkMessageHandler networkMessageHandler,
            SessionManager sessionManager) {

        this.protocolDetectionHandler = protocolDetectionHandler;
        this.genericDecoder = genericDecoder;
        this.networkMessageHandler = networkMessageHandler;
        this.sessionManager = sessionManager;

        logger.info("TrackerPipelineFactory constructed with ProtocolDetectionHandler instance ID: {}",
                System.identityHashCode(this.protocolDetectionHandler));
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        logger.info("Adding ProtocolDetectionHandler to pipeline — instance ID: {}",
                System.identityHashCode(protocolDetectionHandler));

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
