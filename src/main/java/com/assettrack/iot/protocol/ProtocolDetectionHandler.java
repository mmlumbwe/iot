package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Removed @Sharable annotation since we're creating new instances per channel
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector protocolDetector;

    public ProtocolDetectionHandler(ProtocolDetector protocolDetector) {
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // If the message is not a ByteBuf, pass it along immediately.
        // This is crucial if a previous handler or Netty itself passes non-ByteBuf messages.
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Retain the buffer so it can be safely used by subsequent handlers.
        // The last handler consuming the buffer is responsible for releasing it.
        buf.retain();
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data); // Read data without consuming (increasing readerIndex)

            logger.info("ProtocolDetectionHandler: Detecting protocol for raw packet: {}", Hex.encodeHexString(data));
            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

            if (result != null && result.isDetected()) {
                logger.info("ProtocolDetectionHandler: Detected protocol: {} - {}", result.getProtocol(), result.getPacketType());
                ctx.fireChannelRead(result); // Pass the detection result downstream
            } else {
                logger.warn("ProtocolDetectionHandler: No protocol detected by ProtocolDetector for packet: {}", Hex.encodeHexString(data));
                // Even if not detected, we might still want to pass an UNKNOWN result
                // so the next handler can make decisions or log appropriately.
                ctx.fireChannelRead(ProtocolDetector.failure("NO_DETECTION"));
            }

            ctx.fireChannelRead(buf); // Pass the original ByteBuf downstream for decoding

        } catch (Exception e) {
            logger.error("ProtocolDetectionHandler: Error in ProtocolDetectionHandler", e);
            ReferenceCountUtil.release(buf); // Release buffer on error
            ctx.close(); // Close the channel on error
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ProtocolDetectionHandler: Channel error", cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            logger.info("ProtocolDetectionHandler: Channel idle, closing connection");
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }
}