package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
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

    private static final AttributeKey<Boolean> TELTONIKA_AVL_ADDED =
            AttributeKey.valueOf("TELTONIKA_AVL_ADDED");

    /** The name under which this handler is added to the pipeline */
    public static final String NAME = "protocolDetector";

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

        //ProtocolDetectionResult result = protocolDetector.detect(rawData); // Use the byte array for detection
        ChannelPipeline pipeline = ctx.pipeline();
        String protocol = result.getProtocol();

        if (result.isValid()) {
            logger.info("Detected protocol: {}, packetType: {}, version: {}",
                    protocol, result.getPacketType(), result.getVersion());

            // === Teltonika Protocol ===
            if (protocol.equals("TELTONIKA")) {
                if (result.getPacketType().equals("IMEI")) {
                    // For IMEI packets, just pass it down. TeltonikaHandler will send the 0x01 response.
                    // DO NOT send response here.
                    // DO NOT remove this handler here. It needs to stay to detect AVL data packets.
                    logger.info("Teltonika IMEI packet detected. Passing to TeltonikaHandler.");
                    ctx.fireChannelRead(result);
                    ctx.fireChannelRead(buf.retain());
                    return;
                } else { // This indicates an AVL data packet or other Teltonika data after IMEI handshake
                    // Add LengthFieldBasedFrameDecoder only if it hasn't been added yet for this channel
                    if (ctx.channel().attr(TELTONIKA_AVL_ADDED).get() == null || !ctx.channel().attr(TELTONIKA_AVL_ADDED).get()) {
                        pipeline.addBefore(BaseProtocolDecoder.NAME, "teltonika-avl-decoder",
                                new LengthFieldBasedFrameDecoder(
                                        1024 * 1024, // maxFrameLength (e.g., 1MB)
                                        4,           // lengthFieldOffset (from the beginning of the AVL data packet, after 4 zero bytes preamble)
                                        4,           // lengthFieldLength (length of the data field)
                                        4,           // lengthAdjustment (adjust for the 4 bytes of length itself, and the 4 zero bytes preamble)
                                        0            // initialBytesToStrip (no bytes to strip here, as the BaseProtocolDecoder needs the full frame)
                                )
                        );
                        ctx.channel().attr(TELTONIKA_AVL_ADDED).set(true);
                        logger.info("Added Teltonika AVL LengthFieldBasedFrameDecoder for channel {}", ctx.channel().id());
                    }
                    // After adding the decoder, this handler can remove itself as its job for Teltonika is done.
                    pipeline.remove(this);
                    ctx.fireChannelRead(result); // Pass the detection result down
                    ctx.fireChannelRead(buf.retain()); // Pass the buffer down
                    return;
                }
            }

            // === GT06/TK103 Protocol ===
            /*if (protocol.equals("GT06") || protocol.equals("TK103")) {
                // GT06/TK103 devices use a delimiter (0x0D0A) based framing.
                // Only add if not already present to avoid multiple additions on channel reconnect/reset.
                if (pipeline.get("gt06-delimiter-decoder") == null) {
                    pipeline.addBefore(BaseProtocolDecoder.NAME, "gt06-delimiter-decoder",
                            new DelimiterBasedFrameDecoder(
                                    1024, // maxFrameLength
                                    true, // stripDelimiter
                                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                            )
                    );
                    logger.info("Added GT06/TK103 delimiter frame decoder");
                }
                pipeline.remove(this); // Remove once the specific decoder is added
                ctx.fireChannelRead(result);
                ctx.fireChannelRead(buf.retain());
                return;
            }*/

            // === Unknown protocol ===
            pipeline.remove(this); // Remove handler for unknown protocols
            logger.warn("Unknown protocol '{}', removing ProtocolDetectionHandler", protocol);
            ctx.fireChannelRead(result);
            ctx.fireChannelRead(buf.retain());
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