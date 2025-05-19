package com.assettrack.iot.network;

import com.assettrack.iot.network.handlers.NetworkMessageHandler;
import com.assettrack.iot.protocol.BaseProtocolDecoder;
import com.assettrack.iot.protocol.ProtocolDetectionHandler;
import com.assettrack.iot.protocol.Gt06Handler;
import com.assettrack.iot.protocol.ProtocolDetector;
import com.assettrack.iot.session.SessionManager;
import  com.assettrack.iot.handler.network.AcknowledgementHandler;
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


    @Autowired
    public TrackerPipelineFactory(
            ProtocolDetector protocolDetector,
            SessionManager sessionManager, AcknowledgementHandler acknowledgementHandler, CacheManager cacheManager, ProtocolDetectionHandler protocolDetectionHandler
    ) {
        this.protocolDetector = protocolDetector;
        this.sessionManager = sessionManager;
        this.acknowledgementHandler = acknowledgementHandler;
        this.cacheManager = cacheManager;
        this.protocolDetectionHandler = protocolDetectionHandler;
    }

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        // Create new instance for each channel instead of using autowired one
        pipeline.addLast("protocolDetector", new ProtocolDetectionHandler(protocolDetector));

        // 1. Timeout handler
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // 2. Log raw incoming data
        pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO) {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof ByteBuf) {
                    ByteBuf buf = (ByteBuf) msg;
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), bytes);
                    logger.info("Raw message ({} bytes): {}", bytes.length, Hex.encodeHexString(bytes));
                    buf.resetReaderIndex(); // Reset for next handler
                }
                super.channelRead(ctx, msg);
            }
        });

        // 3. Create new Gt06Handler instance per channel
        //Gt06Handler gt06Handler = new Gt06Handler(sessionManager, protocolDetector, acknowledgementHandler);
        pipeline.addLast("gt06Handler", new Gt06Handler(
                sessionManager,
                protocolDetector,
                acknowledgementHandler
        ));

        // 4. Add your business logic handler
        pipeline.addLast("messageHandler", new NetworkMessageHandler(
                sessionManager,  // First required argument
                cacheManager    // Second required argument
        ));

        // 5. Final logging
        pipeline.addLast("processedLogger", new LoggingHandler("Processed-Messages", LogLevel.INFO));

        pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                logger.error("Pipeline error from {}", ctx.channel().remoteAddress(), cause);
                ctx.close();
            }
        });
    }
}