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

        // 1. Protocol detection first
        pipeline.addLast("protocolDetector", new ProtocolDetectionHandler(protocolDetector));

        // 2. Idle state handler
        pipeline.addLast("idleHandler", new IdleStateHandler(30, 0, 0));

        // 3. Protocol-specific handlers
        pipeline.addLast("gt06Handler", new Gt06Handler(
                sessionManager,
                protocolDetector,
                acknowledgementHandler
        ));

        // 4. Raw data logger
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

        // 5. Business logic handler
        pipeline.addLast("messageHandler", new NetworkMessageHandler(
                sessionManager,
                cacheManager
        ));

        // 6. Processed messages logger
        pipeline.addLast("processedLogger", new LoggingHandler("Processed-Messages", LogLevel.DEBUG));

        // 7. Exception handler
        pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                logger.error("Pipeline error", cause);
                ctx.close();
            }
        });
    }
}