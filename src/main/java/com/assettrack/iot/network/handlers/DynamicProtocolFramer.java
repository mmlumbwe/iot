package com.assettrack.iot.network.handlers;

import com.assettrack.iot.protocol.ProtocolDetector;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicProtocolFramer extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DynamicProtocolFramer.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ProtocolDetector.ProtocolDetectionResult result) {
            logger.info("DynamicProtocolFramer: got ProtocolDetectionResult(protocol={}, packetType={})",
                    result.getProtocol(), result.getPacketType());
            if (result.isDetected()) {
                logger.info("DynamicProtocolFramer: configuring pipeline for {}", result.getProtocol());
                String protocol = result.getProtocol();
                logger.info("Dynamically configuring pipeline for protocol: {}", protocol);

                // Remove this handler from the pipeline once its role is fulfilled
                ctx.pipeline().remove(this);

                if ("TELTONIKA".equalsIgnoreCase(protocol)) {
                    if ("IMEI".equalsIgnoreCase(result.getPacketType())) {
                        logger.info("DynamicProtocolFramer: Teltonika IMEI packet. No frame decoder needed for IMEI.");
                        // IMEI packets do not require LengthFieldBasedFrameDecoder.
                        // They are typically handled directly by TeltonikaHandler after initial detection.
                    } else {
                        logger.info("DynamicProtocolFramer: adding Teltonika LengthFieldBasedFrameDecoder for DATA packet.");
                        // Teltonika (e.g., Codec 8) packet structure:
                        // Preamble (4 bytes, 0x00000001)
                        // Data Length (4 bytes, specifies length of AVL Data from Codec ID to CRC)
                        // AVL Data (variable length)
                        // CRC (2 bytes)

                        // Parameters for LengthFieldBasedFrameDecoder:
                        // maxFrameLength: Maximum expected frame length (e.g., 4KB)
                        // lengthFieldOffset: Offset to the start of the length field (after 4-byte preamble)
                        // lengthFieldLength: Length of the length field (4 bytes)
                        // lengthAdjustment: (Preamble length + Length field length) = 4 + 4 = 8 bytes.
                        //                   This adds the bytes before and including the length field to the value of the length field to get total frame length.
                        // initialBytesToStrip: 0 to pass the entire framed packet (including preamble and length field) to the next handler.
                        ctx.pipeline().addFirst("teltonikaFrameDecoder", new LengthFieldBasedFrameDecoder(
                                1024 * 4, // maxFrameLength (e.g., 4KB, adjust as per device max packet size)
                                4,        // lengthFieldOffset: offset to the length field itself (after 4-byte preamble)
                                4,        // lengthFieldLength: length of the length field (4 bytes)
                                8,        // lengthAdjustment: 4 bytes (for Preamble) + 4 bytes (for Length Field itself)
                                0         // initialBytesToStrip: 0 to pass the entire framed packet to the TeltonikaHandler
                        ));
                        logger.info("Added LengthFieldBasedFrameDecoder for Teltonika DATA.");
                    }
                } else if ("GT06".equalsIgnoreCase(protocol)) {
                    // GT06 typically uses 0x0D0A delimiters.
                    // The Gt06Handler expects the 0x0D0A to be present.
                    ctx.pipeline().addFirst("gt06FrameDecoder", new DelimiterBasedFrameDecoder(
                            512, // maxFrameLength (adjust as needed)
                            false, // retain CRLF so Gt06Handler sees full frame length
                            Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                    ));
                    logger.info("Added DelimiterBasedFrameDecoder for GT06.");
                } else {
                    logger.warn("No specific frame decoder defined for protocol: {}. Closing channel.", protocol);
                    ctx.close(); // Close channel for unsupported/unhandled protocol framing
                    return;
                }
            } else {
                logger.warn("Protocol not detected: {}. Closing channel.", result.getError());
                ctx.close(); // Close channel if protocol couldn't be detected
                return;
            }
        }
        // Pass the original message (which could be the initial ByteBuf or the ProtocolDetectionResult)
        // downstream. The ProtocolDetectionResult is needed by GenericProtocolDecoder.
        ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("DynamicProtocolFramer caught exception for channel {}: {}", ctx.channel().id(), cause.getMessage(), cause);
        ctx.close();
    }
}