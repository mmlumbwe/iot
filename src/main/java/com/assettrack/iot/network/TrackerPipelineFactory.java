package com.assettrack.iot.network;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.session.SessionManager;
import  com.assettrack.iot.handler.network.AcknowledgementHandler;
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

    private final ProtocolDetector protocolDetector;
    private final SessionManager sessionManager;
    private final AcknowledgementHandler acknowledgementHandler;
    private final CacheManager cacheManager;
    private final ProtocolDetectionHandler protocolDetectionHandler;
    private final TeltonikaHandler teltonikaHandler;
    private final GenericProtocolDecoder genericDecoder;



    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetector protocolDetector,
            SessionManager sessionManager,
            AcknowledgementHandler acknowledgementHandler,
            CacheManager cacheManager,
            ProtocolDetectionHandler protocolDetectionHandler,
            TeltonikaHandler teltonikaHandler,
            GenericProtocolDecoder genericDecoder
    ) {
        this.protocolDetector = protocolDetector;
        this.sessionManager = sessionManager;
        this.acknowledgementHandler = acknowledgementHandler;
        this.cacheManager = cacheManager;
        this.protocolDetectionHandler = protocolDetectionHandler;
        this.teltonikaHandler = teltonikaHandler;
        this.genericDecoder = genericDecoder;
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // 1. Logging first
        pipeline.addLast(new LoggingHandler("Raw-Inbound", LogLevel.INFO));

        // 2. Protocol detection
        pipeline.addLast("protocolDetector", protocolDetectionHandler);

        // 3. Idle state handler
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // 4. Use the concrete decoder
        pipeline.addLast("decoder", genericDecoder);

        // 5. Business logic
        pipeline.addLast("messageHandler", new NetworkMessageHandler(
                sessionManager,
                cacheManager
        ));

        // 6. Exception handler
        pipeline.addLast(new ChannelDuplexHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                logger.error("Pipeline error", cause);
                ctx.close();
            }
        });
    }
}