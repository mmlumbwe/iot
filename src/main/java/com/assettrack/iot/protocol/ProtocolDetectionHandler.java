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

/**
 * ProtocolDetectionHandler dispatches incoming ByteBufs to the appropriate protocol decoder.
 * It attempts primary detection via ProtocolDetector.detect(...), and if that returns null
 * or a non-detected result, it falls back to Teltonika and GT06 matchers before failing.
 */
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

        buf.retain();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        String hex = Hex.encodeHexString(data);
        logger.info("Protocol detection for packet: {}", hex);

        ProtocolDetector.ProtocolDetectionResult result;
        try {
            result = protocolDetector.detect(data);
        } catch (Exception e) {
            logger.error("Protocol detection error during detect(): {}", e.getMessage(), e);
            result = null;
        }

        // If detect() returned null or did not identify a protocol, try fallbacks
        if (result == null || !result.isDetected()) {
            if (result == null) {
                logger.error("ProtocolDetector returned null for data: {}", hex);
            } else {
                logger.warn("Protocol detection failed: {}", result.getError());
            }

            // 1) Teltonika fallback
            ProtocolDetector.TeltonikaMatcher teltonikaMatcher = new ProtocolDetector.TeltonikaMatcher();
            if (teltonikaMatcher.matches(data)) {
                String packetType = teltonikaMatcher.getPacketType(data);
                logger.info("Fallback detecting Teltonika protocol: {}", packetType);
                ctx.fireChannelRead(
                        ProtocolDetector.ProtocolDetectionResult.success("TELTONIKA", packetType, "1.0")
                );
                ctx.fireChannelRead(buf);
                return;
            }

            // 2) GT06 fallback using Gt06Matcher for accurate packet type
            ProtocolDetector.Gt06Matcher gt06Matcher = new ProtocolDetector.Gt06Matcher();
            if (gt06Matcher.matches(data)) {
                String packetType = gt06Matcher.getPacketType(data);
                logger.info("Fallback detecting GT06 protocol: {}", packetType);
                ctx.fireChannelRead(
                        ProtocolDetector.ProtocolDetectionResult.success("GT06", packetType, "1.0")
                );
                ctx.fireChannelRead(buf);
                return;
            }

            // 3) Total failure: release buffer and propagate failure
            logger.error("No protocol detected and no fallback available");
            ReferenceCountUtil.release(buf);
            ctx.fireChannelRead(
                    ProtocolDetector.ProtocolDetectionResult.failure("DETECTION_ERROR")
            );
            return;
        }

        // Primary detection succeeded
        logger.info("Detected {} protocol: {}", result.getProtocol(), result.getPacketType());
        ctx.fireChannelRead(result);
        ctx.fireChannelRead(buf);
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
