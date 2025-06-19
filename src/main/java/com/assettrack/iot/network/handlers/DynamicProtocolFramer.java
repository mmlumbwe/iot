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
            if (result.isDetected()) {
                String protocol = result.getProtocol();
                logger.info("Dynamically configuring pipeline for protocol: {}", protocol);

                // Remove this handler from the pipeline once its role is fulfilled
                ctx.pipeline().remove(this);

                if ("TELTONIKA".equalsIgnoreCase(protocol)) {
                    // Teltonika (e.g., Codec 8) packet structure:
                    // Preamble (4 bytes, 0x00000001)
                    // Data Length (4 bytes, specifies length of AVL Data from Codec ID to CRC)
                    // AVL Data (variable length)
                    // CRC (4 bytes)

                    // lengthFieldOffset: 4 (after the 4-byte preamble)
                    // lengthFieldLength: 4 (the length field itself is 4 bytes)
                    // lengthAdjustment: -8 (Data Length field counts from Codec ID to CRC.
                    //                      We need to account for the Preamble and the Length Field itself.
                    //                      So, total packet = Preamble(4) + LengthField(4) + (Data Length from field) + CRC(4)
                    //                      The LengthFieldBasedFrameDecoder calculates: frame = actual_length + lengthAdjustment
                    //                      So if LengthField = L, total frame size = 4 + 4 + L + 4.
                    //                      We want the decoder to output (4 + 4 + L + 4) bytes.
                    //                      Length field value is L. So, L + lengthAdjustment = (4+4+L+4)
                    //                      lengthAdjustment = 8. (Precludes the preamble and length field in the length calculation itself for simpler parsing)
                    //                      Wait, standard Teltonika length field *includes* Codec ID to CRC.
                    //                      So, reported length is (Codec ID + AVL Data + CRC).
                    //                      Total packet size = 4 (Preamble) + 4 (Data Length) + Reported Length.
                    //                      LengthFieldBasedFrameDecoder expects frame length to be at 'lengthFieldOffset'.
                    //                      So here: lengthFieldOffset = 4 (preamble is 4 bytes).
                    //                      lengthFieldLength = 4.
                    //                      lengthAdjustment = 4 (for CRC) + 4 (for Preamble) = 8.
                    //                      initialBytesToStrip = 0 (we want TeltonikaHandler to see the full packet including preamble).
                    ctx.pipeline().addFirst("teltonikaFrameDecoder", new LengthFieldBasedFrameDecoder(
                            1024 * 4, // maxFrameLength (e.g., 4KB, adjust as per device max packet size)
                            4,        // lengthFieldOffset: offset to the length field itself (after 4-byte preamble)
                            4,        // lengthFieldLength: length of the length field (4 bytes)
                            8,        // lengthAdjustment: 4 bytes (for CRC) + 4 bytes (for Preamble)
                            0         // initialBytesToStrip: 0 to pass the entire framed packet to the TeltonikaHandler
                    ));
                    logger.info("Added LengthFieldBasedFrameDecoder for Teltonika.");
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
