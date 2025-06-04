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

        // 1. Protocol detection (shared, safe due to @Sharable)
        if (pipeline.get("protocolDetector") == null) {
            pipeline.addLast("protocolDetector", protocolDetectionHandler);
        }

        // 2. Idle timeout
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // 3. Route to GT06 or Teltonika after protocol is detected
        pipeline.addLast("protocolRouter", new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                String protocol = ctx.channel().attr(ProtocolDetectionHandler.PROTOCOL_ATTR).get();

                if ("GT06".equalsIgnoreCase(protocol)) {
                    if (ctx.pipeline().get("gt06Handler") == null) {
                        logger.info("Routing to GT06 handler");
                        ctx.pipeline().addAfter(ctx.name(), "gt06Handler", gt06Handler);
                    }
                } else if ("TELTONIKA".equalsIgnoreCase(protocol)) {
                    if (ctx.pipeline().get("teltonikaHandler") == null) {
                        logger.info("Routing to Teltonika handler");
                        ctx.pipeline().addAfter(ctx.name(), "teltonikaHandler", (ChannelHandler) teltonikaHandler);
                    }
                } else {
                    logger.warn("Unknown or unsupported protocol: '{}', closing connection", protocol);
                    ctx.close();
                    return;
                }

                // Remove this router from pipeline after use
                ctx.pipeline().remove(this);

                // Forward the retained message
                ctx.fireChannelRead(msg.retain());
            }
        });

        // 4. Log raw bytes for debugging
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

        // 5. Message processing
        pipeline.addLast("messageHandler", new NetworkMessageHandler(sessionManager, cacheManager));

        // 6. Send responses or ACKs
        pipeline.addLast("ackHandler", acknowledgementHandler);

        // 7. Log structured/parsed messages
        pipeline.addLast("processedLogger", new LoggingHandler("Processed-Messages", LogLevel.DEBUG));

        // 8. Global exception handler
        pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                logger.error("Pipeline error", cause);
                ctx.close();
            }
        });
    }
}
