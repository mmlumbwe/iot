package com.assettrack.iot.network;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.handler.network.AcknowledgementHandler;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.apache.commons.codec.binary.Hex;
import org.apache.coyote.ProtocolException;
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

        // Log the instance ID of the injected protocol detection handler
        logger.info("TrackerPipelineFactory constructed with ProtocolDetectionHandler instance ID: {}",
                System.identityHashCode(this.protocolDetectionHandler));
    }

    @Override
    protected void initChannel(Channel channel) {
        logger.info("Adding ProtocolDetectionHandler to pipeline — instance ID: {}", System.identityHashCode(protocolDetectionHandler));

        ChannelPipeline pipeline = channel.pipeline();

        // Log the instance ID again when adding to the pipeline
        logger.info("Adding ProtocolDetectionHandler to pipeline — instance ID: {}",
                System.identityHashCode(protocolDetectionHandler));

        // 1. Raw logging
        if (pipeline.get("rawLogger") == null) {
            pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO));
        }

        // 2. Protocol detector
        if (pipeline.get("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", protocolDetectionHandler);
        }

        // 3. Idle handler
        if (pipeline.get("idleHandler") == null) {
            pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));
        }

        // 4. Decoder
        if (pipeline.get("decoder") == null) {
            pipeline.addLast("decoder", genericDecoder);
        }

        // 5. Business logic
        if (pipeline.get("messageHandler") == null) {
            pipeline.addLast("messageHandler", networkMessageHandler);
        }

        // 6. Exception handler (anonymous is OK here)
        pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                logger.error("Pipeline error", cause);
                ctx.close();
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                sessionManager.removeSession(ctx.channel());
                super.channelInactive(ctx);
            }
        });
    }
}
