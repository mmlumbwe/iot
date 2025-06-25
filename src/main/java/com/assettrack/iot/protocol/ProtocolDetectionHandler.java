package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey; // Import this
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProtocolDetectionHandler dispatches incoming ByteBufs to the appropriate protocol decoder.
 * It attempts primary detection via ProtocolDetector.detect(...), and if that returns null
 * or a non-detected result, it falls back to Teltonika and GT06 matchers.
 * It no longer installs LengthFieldBasedFrameDecoder for Teltonika AVL data; this responsibility is moved to DynamicProtocolFramer.
 * THIS NOW does both
 */
@Sharable
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector detector = new ProtocolDetector();

    // Define a static AttributeKey for storing the detected protocol
    public static final AttributeKey<String> DETECTED_PROTOCOL_KEY = AttributeKey.newInstance("detectedProtocol");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        buf.retain();
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            String hex = Hex.encodeHexString(data);
            logger.debug("Protocol detection for packet: {}", hex);

            ProtocolDetector.ProtocolDetectionResult result;
            try {
                result = detector.detect(data);
            } catch (Exception e) {
                logger.error("Protocol detection error: {}", e.getMessage(), e);
                result = null;
            }

            if (result == null || !result.isDetected()) {
                if (result == null) {
                    logger.warn("ProtocolDetector returned null for data: {}", hex);
                } else {
                    logger.warn("Protocol detection failed: {}", result.getError());
                }

                // Astra Telematics fallback
                ProtocolDetector.AstraMatcher astraMatcher = new ProtocolDetector.AstraMatcher();
                if (astraMatcher.matches(data)) {
                    logger.info("Detected ASTRA_AT240 protocol");
                    ProtocolDetector.ProtocolDetectionResult astraDetectionResult =
                            ProtocolDetector.ProtocolDetectionResult.success(
                                    "ASTRA_AT240",
                                    astraMatcher.getPacketType(data),
                                    "1.0"
                            );
                    // Set the correct AttributeKey with the ProtocolDetectionResult object
                    // This is the key change for Astra delegation improvement.
                    ctx.channel().attr(ProtocolDetector.PROTOCOL_DETECTION_RESULT_KEY).set(astraDetectionResult);
                    // Keep the existing message firing for compatibility with DynamicProtocolFramer
                    ctx.fireChannelRead(astraDetectionResult);
                    ctx.fireChannelRead(buf.retain());
                    return;
                }

                // Teltonika fallback
                ProtocolDetector.TeltonikaMatcher teltonikaMatcher = new ProtocolDetector.TeltonikaMatcher();
                if (teltonikaMatcher.matches(data)) {
                    // Teltonika detection (IMEI or AVL data)
                    handleTeltonikaProtocol(ctx, buf, teltonikaMatcher.getPacketType(data));
                    return;
                }

                // GT06 fallback
                ProtocolDetector.Gt06Matcher gt06Matcher = new ProtocolDetector.Gt06Matcher();
                if (gt06Matcher.matches(data)) {
                    String packetType = gt06Matcher.getPacketType(data);
                    logger.info("Detected GT06 protocol: {}", packetType);
                    // Store the detected protocol in channel attributes
                    ctx.channel().attr(DETECTED_PROTOCOL_KEY).set("GT06");
                    ctx.fireChannelRead(
                            ProtocolDetector.ProtocolDetectionResult.success("GT06", packetType, "1.0")
                    );
                    ctx.fireChannelRead(buf.retain());
                    return;
                }

                logger.error("No protocol detected");
                ReferenceCountUtil.release(buf);
                ctx.fireChannelRead(
                        ProtocolDetector.ProtocolDetectionResult.failure("DETECTION_ERROR")
                );
                return;
            }

            // Primary detection succeeded
            if ("TELTONIKA".equalsIgnoreCase(result.getProtocol())) {
                handleTeltonikaProtocol(ctx, buf, result.getPacketType());
            } else {
                logger.info("Detected {} protocol: {}", result.getProtocol(), result.getPacketType());
                // Store the detected protocol in channel attributes for primary detection
                ctx.channel().attr(DETECTED_PROTOCOL_KEY).set(result.getProtocol());
                ctx.fireChannelRead(result);
                ctx.fireChannelRead(buf.retain());
            }
        } finally {
            buf.release();
        }
    }

    private void handleTeltonikaProtocol(ChannelHandlerContext ctx, ByteBuf buf, String packetType) {
        logger.info("Processing Teltonika packet: {}", packetType);

        // For Teltonika (IMEI or DATA), simply fire the detection result and the buffer.
        // DynamicProtocolFramer downstream will handle the addition of LengthFieldBasedFrameDecoder.
        logger.info("Forwarding Teltonika {} frame for framing by DynamicProtocolFramer.", packetType);
        // Store the detected protocol in channel attributes
        ctx.channel().attr(DETECTED_PROTOCOL_KEY).set("TELTONIKA");
        ctx.fireChannelRead(
                ProtocolDetector.ProtocolDetectionResult.success("TELTONIKA", packetType, "1.0")
        );
        ctx.fireChannelRead(buf.retain());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Protocol detection error", cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            logger.info("Closing idle connection");
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }
}