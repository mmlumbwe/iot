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
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
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

        // ✅ 3. Frame decoder to extract full GT06 packets
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                1024, // maxFrameLength
                2,    // lengthFieldOffset (length is the 3rd byte)
                1,    // lengthFieldLength (1 byte)
                4,    // lengthAdjustment: checksum (2 bytes) + ending (0x0D 0x0A) = 4
                0     // initialBytesToStrip: pass full packet to GT06 handler
        ));

        // 4. GT06 protocol-specific handler
        pipeline.addLast("gt06Handler", new Gt06Handler(
                sessionManager,
                protocolDetector,
                acknowledgementHandler
        ));

        // 5. Raw data logger
        pipeline.addLast("rawLogger", new LoggingHandler("Raw-Inbound", LogLevel.INFO) {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof ByteBuf) {
                    ByteBuf buf = (ByteBuf) msg;
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), bytes);
                    logger.info("Raw message ({} bytes): {}", bytes.length, Hex.encodeHexString(bytes));
                    buf.resetReaderIndex();
                }
                super.channelRead(ctx, msg);
            }
        });

        // 6. Business logic handler
        pipeline.addLast("messageHandler", new NetworkMessageHandler(
                sessionManager,
                cacheManager
        ));

        // 7. Processed messages logger
        pipeline.addLast("processedLogger", new LoggingHandler("Processed-Messages", LogLevel.DEBUG));

        // 8. Exception handler
        pipeline.addLast("exceptionHandler", new ChannelDuplexHandler() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                logger.error("Pipeline error", cause);
                ctx.close();
            }
        });
    }

}