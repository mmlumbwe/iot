package com.assettrack.iot.protocol;

import com.assettrack.iot.protocol.ProtocolDetector.ProtocolDetectionResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    /**
     * Attribute on each channel indicating whether we've already installed
     * the Teltonika AVL frame decoder.
     */
    private static final AttributeKey<Boolean> TELTONIKA_AVL_ADDED =
            AttributeKey.valueOf("TELTONIKA_AVL_ADDED");

    /** The name under which this handler is added to the pipeline */
    public static final String NAME = "protocolDetector";

    private final ProtocolDetector protocolDetector;
    private final TeltonikaHandler teltonikaHandler;
    private final Gt06Handler gt06Handler;

    public ProtocolDetectionHandler(
            ProtocolDetector protocolDetector,
            TeltonikaHandler teltonikaHandler,
            Gt06Handler gt06Handler
    ) {
        this.protocolDetector = protocolDetector;
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler = gt06Handler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;
        logger.debug("ProtocolDetectionHandler: ENTER channelRead, raw hex = {}", ByteBufUtil.hexDump(buf));

        try {
            // Log raw received data in hexadecimal
            logger.info("Raw Received Data (Hex): {}", ByteBufUtil.hexDump(buf).toUpperCase());

            if (buf.readableBytes() == 0) {
                return;
            }

            // Create a byte array copy of the readable bytes from the ByteBuf.
            // This ensures the original ByteBuf's readerIndex is not advanced for subsequent handlers.
            byte[] rawData = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), rawData); // Copies bytes without modifying readerIndex

            ProtocolDetectionResult result = protocolDetector.detect(rawData); // Use the byte array for detection
            ChannelPipeline pipeline = ctx.pipeline();
            String protocol = result.getProtocol();

            if (buf.readableBytes() == 0) {
                return;
            }
            // ...
            if (result.isValid()) {
                logger.info("ProtocolDetectionHandler: detected {} packetType={}, version={}",
                        protocol, result.getPacketType(), result.getVersion());

                if ("TELTONIKA".equals(protocol)) {
                    if ("IMEI".equals(result.getPacketType())) {
                        logger.info("ProtocolDetectionHandler: passing IMEI frame downstream");
                        ctx.fireChannelRead(result);
                        ctx.fireChannelRead(buf.retain());
                        return;
                    } else {
                        logger.info("ProtocolDetectionHandler: TELTONIKA DATA detected — adding framer & removing self");
                        // ... add decoder ...
                        pipeline.remove(this);
                        ctx.fireChannelRead(result);
                        ctx.fireChannelRead(buf.retain());
                        return;
                    }
                }
                // …
            }
            // …
        } finally {
            buf.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ProtocolDetectionHandler: Channel error", cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            logger.info("ProtocolDetectionHandler: Channel idle, closing connection");
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }
}