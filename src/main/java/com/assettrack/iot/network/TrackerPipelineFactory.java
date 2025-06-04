package com.assettrack.iot.network;

import com.assettrack.iot.handler.network.AcknowledgementHandler;
import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.*;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.apache.commons.codec.binary.Hex;
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
    private final Gt06Handler gt06Handler;
    private final TeltonikaHandler teltonikaHandler;

    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetector protocolDetector,
            SessionManager sessionManager,
            AcknowledgementHandler acknowledgementHandler,
            CacheManager cacheManager,
            ProtocolDetectionHandler protocolDetectionHandler,
            Gt06Handler gt06Handler,
            TeltonikaHandler teltonikaHandler
    ) {
        this.protocolDetector = protocolDetector;
        this.sessionManager = sessionManager;
        this.acknowledgementHandler = acknowledgementHandler;
        this.cacheManager = cacheManager;
        this.protocolDetectionHandler = protocolDetectionHandler;
        this.gt06Handler = gt06Handler;
        this.teltonikaHandler = teltonikaHandler;
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // 1. Detect protocol (fills attributes)
        pipeline.addLast("protocolDetector", protocolDetectionHandler);

        // 2. Idle state timeout
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // 3. Dynamically choose handler based on detection
        pipeline.addLast("protocolRouter", new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                String protocol = ctx.channel().attr(ProtocolDetectionHandler.PROTOCOL_ATTR).get();

                if ("GT06".equalsIgnoreCase(protocol)) {
                    logger.info("Routing to GT06 handler");
                    ctx.pipeline().addAfter(ctx.name(), "gt06Handler", gt06Handler);
                } else if ("TELTONIKA".equalsIgnoreCase(protocol)) {
                    logger.info("Routing to Teltonika handler");
                    ctx.pipeline().addAfter(ctx.name(), "teltonikaHandler", (ChannelHandler) teltonikaHandler);
                } else {
                    logger.warn("Unknown or unsupported protocol: {}", protocol);
                    ctx.close();
                    return;
                }

                // Remove router to avoid duplicate routing
                ctx.pipeline().remove(this);

                // Pass along the message to the newly added handler
                ctx.fireChannelRead(msg.retain());
            }
        });

        // 4. Optional: log raw incoming bytes
        pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO) {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof ByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), bytes);
                    logger.info("Raw message ({} bytes): {}", bytes.length, Hex.encodeHexString(bytes));
                    buf.resetReaderIndex(); // preserve reader index
                }
                super.channelRead(ctx, msg);
            }
        });

        // 5. Business logic handler
        pipeline.addLast("messageHandler", new NetworkMessageHandler(sessionManager, cacheManager));

        // 6. Outbound response handler (ACKs, etc.)
        pipeline.addLast("ackHandler", acknowledgementHandler);

        // 7. Processed logging
        pipeline.addLast("processedLogger", new LoggingHandler("Processed-Messages", LogLevel.DEBUG));

        // 8. Exception catch-all
        pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                logger.error("Pipeline error", cause);
                ctx.close();
            }
        });
    }
}
