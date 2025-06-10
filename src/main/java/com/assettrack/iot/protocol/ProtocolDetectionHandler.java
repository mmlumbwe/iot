package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.assettrack.iot.protocol.BaseProtocolDecoder.PROTOCOL_HEADER_1;
import static com.assettrack.iot.protocol.BaseProtocolDecoder.PROTOCOL_HEADER_2;

// Removed @Sharable annotation since we're creating new instances per channel
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector protocolDetector;

    public ProtocolDetectionHandler(ProtocolDetector protocolDetector) {
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            buf.retain();

            logger.info("Protocol detection for packet: {}", Hex.encodeHexString(data));
            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

            if (result == null) {
                logger.error("ProtocolDetector returned null for data: {}", Hex.encodeHexString(data));
                ReferenceCountUtil.release(buf);
                // Propagate a failure result downstream:
                ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.failure("DETECTOR_RETURNED_NULL"));
                return;
            }

            // This check is now safe since detect() never returns null
            if (result.isDetected()) {
                logger.info("Detected {} protocol: {}", result.getProtocol(), result.getPacketType());
                ctx.fireChannelRead(result);
                ctx.fireChannelRead(buf);
            } else {
                logger.warn("Protocol detection failed: {}", result.getError());

                // Special handling for GT06-like packets
                if (data.length >= 2 && data[0] == 0x78 && data[1] == 0x78) {
                    logger.info("Processing as GT06 despite detection failure");
                    ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.success("GT06", "FALLBACK_DETECT", "1.0"));
                    ctx.fireChannelRead(buf);
                } else {
                    logger.error("No protocol detected and no fallback available");
                    ReferenceCountUtil.release(buf);
                    ctx.fireChannelRead(result); // Send the failure result downstream
                }
            }
        } catch (Exception e) {
            logger.error("Protocol detection error", e);
            ReferenceCountUtil.release(buf);
            ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.failure("DETECTION_ERROR"));
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