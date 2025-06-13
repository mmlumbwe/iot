package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static com.assettrack.iot.protocol.BaseProtocolDecoder.PROTOCOL_HEADER_1;
import static com.assettrack.iot.protocol.BaseProtocolDecoder.PROTOCOL_HEADER_2;

/**
 * Dynamically detects protocol (Teltonika, GT06, TK103) and inserts appropriate framers.
 * For Teltonika, it handles the IMEI handshake and then switches to AVL data framing.
 */
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    public static final AttributeKey<String> DETECTED_PROTOCOL_KEY = AttributeKey.valueOf("detectedProtocol");

    private final ProtocolDetector protocolDetector;
    private final TeltonikaHandler teltonikaHandler;
    private final Gt06Handler gt06Handler;

    // Track if the Teltonika IMEI handshake was completed
    private boolean teltonikaImeiHandled = false;

    public ProtocolDetectionHandler(
            ProtocolDetector protocolDetector,
            TeltonikaHandler teltonikaHandler,
            Gt06Handler gt06Handler) {
        this.protocolDetector = protocolDetector;
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler = gt06Handler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Retain the buffer if it's going to be used by multiple handlers or for logging.
        // It will be released at the end of this method or by a downstream handler.
        buf.retain();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);

        ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

        if (result.isSuccess()) {
            String protocol = result.getProtocol();
            String packetType = result.getPacketType();

            if ("TELTONIKA".equalsIgnoreCase(protocol)) {
                if ("IMEI".equalsIgnoreCase(packetType)) {
                    // This is an IMEI packet, respond and prepare for AVL
                    if (!teltonikaImeiHandled) {
                        teltonikaImeiHandled = true;
                        // Send IMEI response
                        byte[] imeiResponse = teltonikaHandler.generateResponse(null); // Assuming this generates the correct IMEI ack
                        ctx.writeAndFlush(Unpooled.wrappedBuffer(imeiResponse));
                        logger.info("Sent IMEI response for Teltonika IMEI packet. IMEI: {}", new String(data, 2, 15, StandardCharsets.US_ASCII));
                    }

                    // Dynamically configure the pipeline for subsequent AVL data packets.
                    ChannelPipeline pipeline = ctx.pipeline();

                    // Remove existing IMEI framer if it was ever added as a placeholder
                    if (pipeline.get("teltonikaImeiFrame") != null) {
                        pipeline.remove("teltonikaImeiFrame");
                        logger.info("Removed teltonikaImeiFrame.");
                    }

                    // Add the AVL framer if not already present
                    if (pipeline.get("teltonikaAvlFrame") == null) {
                        // Add before "decoder" to ensure framing happens before decoding business logic
                        pipeline.addBefore("decoder", "teltonikaAvlFrame",
                                new LengthFieldBasedFrameDecoder(
                                        1024 * 1024, // maxFrameLength
                                        4,           // lengthFieldOffset (preamble + length field)
                                        4,           // lengthFieldLength
                                        4,           // lengthAdjustment (excluding CRC and count)
                                        8,           // initialBytesToStrip (preamble + length field)
                                        true         // failFast
                                )
                        );
                        logger.info("Added teltonikaAvlFrame for Teltonika AVL data after IMEI detection.");
                    }

                    // Release the current ByteBuf for the IMEI packet.
                    // This IMEI packet itself is NOT an AVL data frame and should not be passed to the new framer.
                    ReferenceCountUtil.release(buf);

                    // Remove this ProtocolDetectionHandler as its job for Teltonika IMEI handshake is done.
                    // Subsequent Teltonika packets will be handled by teltonikaAvlFrame.
                    if (pipeline.get("protocolDetector") != null) {
                        pipeline.remove(this);
                        logger.info("ProtocolDetectionHandler removed for TELTONIKA protocol after IMEI handshake completion.");
                    }
                    return; // Crucially, stop processing this current IMEI message here.
                }
                // If it's Teltonika but not IMEI (i.e., expected AVL data)
                else {
                    // Ensure teltonikaAvlFrame is in place if not already.
                    ChannelPipeline pipeline = ctx.pipeline();
                    if (pipeline.get("teltonikaAvlFrame") == null) {
                        pipeline.addBefore("decoder", "teltonikaAvlFrame",
                                new LengthFieldBasedFrameDecoder(
                                        1024 * 1024,
                                        4, 4, 4, 8, true
                                )
                        );
                        logger.info("Added teltonikaAvlFrame for Teltonika AVL data (non-IMEI detected initially).");
                    }

                    // Remove this handler once framing is set for Teltonika AVL.
                    if (pipeline.get("protocolDetector") != null) {
                        pipeline.remove(this);
                        logger.info("ProtocolDetectionHandler removed for TELTONIKA protocol (initial AVL data).");
                    }
                    // Continue processing this message, as it is expected to be an AVL data packet.
                    ctx.fireChannelRead(result); // Pass the detection result
                    ctx.fireChannelRead(buf); // Pass the original ByteBuf to the new framer
                    return;
                }
            } else { // Handling for other protocols (GT06, TK103)
                // Existing logic for GT06/TK103 or other protocols.
                // This typically involves adding a DelimiterBasedFrameDecoder or similar.
                setupFramingAndRemoveSelf(ctx.pipeline(), protocol); // Utilize helper to setup framing.
                ctx.fireChannelRead(result); // Pass the detection result downstream
                ctx.fireChannelRead(buf); // Pass the original ByteBuf to the new framer
                return;
            }
        } else {
            // Fallback if primary detection failed via ProtocolDetector.detect(data)
            // Attempt to detect GT06 as a fallback if the primary detection failed
            ProtocolDetector.Gt06Matcher gt06Matcher = new ProtocolDetector.Gt06Matcher();
            if (gt06Matcher.matches(data)) {
                String packetType = gt06Matcher.getPacketType(data);
                logger.info("Fallback detecting GT06 protocol: {}", packetType);
                setupFramingAndRemoveSelf(ctx.pipeline(), "GT06"); // Set up GT06 framing
                ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.success("GT06", packetType, "1.0"));
                ctx.fireChannelRead(buf);
                return;
            }

            // If still no protocol detected, release buffer and propagate failure
            logger.error("No protocol detected and no fallback available for data: {}", Hex.encodeHexString(data));
            ReferenceCountUtil.release(buf); // Release the buffer as it won't be processed
            ctx.fireChannelRead(
                    ProtocolDetector.ProtocolDetectionResult.failure("DETECTION_ERROR")
            );
            return;
        }
    }

    // Helper method to set up framing for GT06/TK103 and remove this handler
    // (Ensure this method exists and correctly handles the pipeline)
    private void setupFramingAndRemoveSelf(ChannelPipeline pipeline, String protocol) {
        switch (protocol) {
            case "GT06":
            case "TK103":
                if (pipeline.get("gt06Tk103Frame") == null) {
                    pipeline.addBefore("protocolDetector", "gt06Tk103Frame",
                            new DelimiterBasedFrameDecoder(
                                    1024, true,
                                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                            )
                    );
                    logger.info("Added gt06Tk103Frame for {} protocol.", protocol);
                }
                break;
            default:
                logger.warn("No specific framing configured for non-Teltonika protocol: {}. Removing ProtocolDetectionHandler.", protocol);
        }
        if (pipeline.get("protocolDetector") != null) {
            pipeline.remove(this);
            logger.info("ProtocolDetectionHandler removed for {} protocol.", protocol);
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