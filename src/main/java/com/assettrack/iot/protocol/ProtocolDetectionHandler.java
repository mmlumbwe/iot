package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects incoming protocol frames and, for Teltonika AVL data, installs
 * a LengthFieldBasedFrameDecoder before routing the raw bytes down the pipeline.
 * Extended to also install LengthFieldBasedFrameDecoder for GT06 packets.
 */
@Component
@Sharable
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector detector = new ProtocolDetector(); // Ensure this is the new ProtocolDetector

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.channelRead(ctx, msg);
            return;
        }
        try {
            // Log every raw buffer we see
            logger.info("ProtocolDetectionHandler: ENTER channelRead, raw hex = {}", ByteBufUtil.hexDump(buf));

            // Copy bytes for detection without changing readerIndex
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            // Use the new ProtocolDetector's detect method
            ProtocolDetector.ProtocolDetectionResult result = detector.detect(data);

            if (result.isDetected()) {
                ChannelPipeline pipeline = ctx.pipeline();

                // If Teltonika AVL data is detected, inject the specific framer
                if ("TELTONIKA".equalsIgnoreCase(result.getProtocol()) && result.getPacketType().startsWith("AVL_DATA_CODEC_")) {
                    logger.info("ProtocolDetectionHandler: TELTONIKA DATA packet detected — installing frame decoder and removing self");
                    pipeline.addFirst("teltonikaFrameDecoder",
                            new LengthFieldBasedFrameDecoder(
                                    10240,  // maxFrameLength: Maximum length of the entire Teltonika packet
                                    4,      // lengthFieldOffset: Length field starts after 4-byte preamble.
                                    4,      // lengthFieldLength: Length field is 4 bytes.
                                    2,      // lengthAdjustment: Add 2 bytes for CRC-16 (not included in length field).
                                    8       // initialBytesToStrip: Strip preamble (4) + data length (4).
                            )
                    );
                    pipeline.remove(this); // Remove self after injecting the framer
                    ctx.fireChannelRead(buf.retain()); // Re-fire the current buffer so the new framer sees it immediately
                }
                // If GT06 protocol is detected, inject its specific framer
                else if ("GT06".equalsIgnoreCase(result.getProtocol())) {
                    logger.info("ProtocolDetectionHandler: GT06 packet detected — installing frame decoder and removing self");
                    pipeline.addFirst("gt06FrameDecoder",
                            new LengthFieldBasedFrameDecoder(
                                    10240, // maxFrameLength: Maximum expected length of a GT06 packet
                                    2,     // lengthFieldOffset: The length field starts at index 2 (after 78 78)
                                    1,     // lengthFieldLength: The length field itself is 1 byte
                                    4,     // lengthAdjustment: Add 4 bytes (2 for headers, 2 for CRC/Terminator) to the declared length to get the total frame length.
                                    3      // initialBytesToStrip: Strip the 2-byte header (78 78) and the 1-byte length field.
                            )
                    );
                    pipeline.remove(this); // Remove self after injecting the framer
                    ctx.fireChannelRead(buf.retain()); // Re-fire the current buffer so the new framer sees it immediately
                }
                else {
                    // For other detected protocols (e.g., Teltonika IMEI, TK103),
                    // simply fire the detection result and the buffer downstream.
                    logger.info("Detected {} protocol (PacketType: {}): {}", result.getProtocol(), result.getPacketType(), ByteBufUtil.hexDump(data));
                    // Store the detection result in channel attributes for BaseProtocolDecoder
                    ctx.channel().attr(ProtocolDetector.PROTOCOL_DETECTION_RESULT_KEY).set(result);
                    ctx.fireChannelRead(buf.retain()); // Retain before firing to ensure it's not released prematurely
                }
            } else {
                // No protocol detected by the new ProtocolDetector
                logger.warn("ProtocolDetectionHandler: No protocol detected for incoming data. Error: {}. Releasing buffer.", result.getError());
                ReferenceCountUtil.release(buf); // Release buffer if no handler processes it
            }
        } finally {
            // No general release here, as handled paths explicitly retain, and unhandled paths release.
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ProtocolDetectionHandler: Channel error", cause);
        ctx.close();
    }
}