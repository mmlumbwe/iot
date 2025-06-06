package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector protocolDetector;

    public ProtocolDetectionHandler(ProtocolDetector protocolDetector) {
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // If the message is not a ByteBuf, pass it along immediately.
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data); // Read data without consuming (increasing readerIndex)

            logger.info("Detecting protocol for raw packet: {}", Hex.encodeHexString(data));
            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

            if (result != null) {
                logger.info("Detected protocol: {} - {}", result.getProtocol(), result.getPacketType());
                ctx.fireChannelRead(result); // Pass the detection result downstream
            } else {
                logger.warn("No protocol detected for packet: {}", Hex.encodeHexString(data));
            }

            ctx.fireChannelRead(buf); // Pass the original ByteBuf downstream for decoding
            // Note: ByteBuf's lifecycle will now be managed by the next handler in the pipeline.

        } catch (Exception e) {
            logger.error("Error in ProtocolDetectionHandler", e);
            ctx.close(); // Close the channel on error
        }
    }

    // The isTeltonikaImeiPacket method is removed from here.
    // ProtocolDetector and TeltonikaHandler will handle IMEI detection and processing.

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel error in ProtocolDetectionHandler", cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            logger.info("Channel idle, closing connection from ProtocolDetectionHandler");
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }
}