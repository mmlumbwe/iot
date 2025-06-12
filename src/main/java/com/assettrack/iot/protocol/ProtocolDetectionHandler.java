package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.AttributeKey; // Import AttributeKey
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Dynamically detects protocol (Teltonika, GT06, TK103) and inserts appropriate framers.
 * For Teltonika, it specifically handles the IMEI handshake and then adds the AVL data framer.
 */
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    // AttributeKey to store the detected protocol type in the Channel attributes
    public static final AttributeKey<String> DETECTED_PROTOCOL_KEY = AttributeKey.valueOf("detectedProtocol");

    private final ProtocolDetector protocolDetector;
    private final TeltonikaHandler teltonikaHandler;
    private final Gt06Handler gt06Handler;

    // State to track if IMEI handshake has occurred for Teltonika
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

        // Get a copy of the readable bytes from the buffer for detection
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);

        // Retain the buffer if it will be passed downstream by fireChannelRead
        // and released later by another handler.
        // Or, if this handler fully consumes the buffer and does not pass it downstream,
        // it should release it here. For initial detection, we pass it along.
        ReferenceCountUtil.retain(msg);

        try {
            if (!teltonikaImeiHandled) { // Only attempt protocol detection if IMEI is not handled
                ProtocolDetector.ProtocolDetectionResult detectionResult = protocolDetector.detect(data);

                if (detectionResult.isValid() && "TELTONIKA".equals(detectionResult.getProtocol()) && "IMEI".equals(detectionResult.getPacketType())) {
                    logger.info("Detected TELTONIKA protocol: {}, Packet Type: {}", detectionResult.getVersion(), detectionResult.getPacketType());

                    // Store the detected protocol in Channel attributes
                    ctx.channel().attr(DETECTED_PROTOCOL_KEY).set(detectionResult.getProtocol());

                    // Setup appropriate framers and remove this handler from the pipeline
                    setupFramingAndRemoveSelf(ctx.pipeline(), detectionResult.getProtocol());

                    // After adding framer, immediately respond to IMEI
                    ctx.writeAndFlush(Unpooled.copiedBuffer(new byte[]{0x01}));
                    logger.info("Sent login request (0x01) to device: {}", Hex.encodeHexString(data).substring(4)); // Extract IMEI
                    teltonikaImeiHandled = true; // Mark IMEI as handled
                } else {
                    logger.warn("Unknown protocol or packet type: {}", detectionResult);
                    // Attempt to detect other protocols or close channel if not handled
                    // This will also remove the ProtocolDetectionHandler if a framer is added
                    // or if no specific framing is needed and it's time to remove it.
                    setupFramingAndRemoveSelf(ctx.pipeline(), detectionResult.getProtocol());
                    ctx.close(); // Close if initial detection fails for expected protocols
                    return; // Prevent passing to downstream if channel is closed
                }
            }
            // Always pass the original message along the pipeline so it can be handled by other handlers
            // If teltonikaImeiHandled is true, framers are already in place and will process this buffer
            // before it reaches GenericProtocolDecoder.
            ctx.fireChannelRead(msg);
        } finally {
            // No need to release here if retain was called and it's passed downstream
            // Netty's pipeline handles release for handlers that pass ByteBufs.
        }
    }

    private void setupFramingAndRemoveSelf(ChannelPipeline pipeline, String protocol) {
        switch (protocol) {
            case "TELTONIKA":
                // IMEI handshake is 1 byte ACK. AVL data requires LengthFieldBasedFrameDecoder
                // Add LengthFieldBasedFrameDecoder for IMEI responses (1-byte ACK). This is usually handled by the response itself.
                // The main Teltonika framing for AVL data needs to be after IMEI.
                // The 'teltonikaImeiFrame' is likely for receiving the initial IMEI. This part might need re-evaluation
                // if it's meant for framing the IMEI packet itself, which is fixed length.
                if (pipeline.get("teltonikaImeiFrame") == null) {
                    pipeline.addBefore("protocolDetector", "teltonikaImeiFrame",
                            new LengthFieldBasedFrameDecoder(
                                    1024 * 1024, 0, 2, 0, 2, true // maxFrameLength, lengthFieldOffset, lengthFieldLength, lengthAdjustment, initialBytesToStrip, failFast
                            )
                    );
                    logger.info("Added teltonikaImeiFrame for Teltonika IMEI.");
                }

                // AVL data: skip 4-byte preamble, then 4-byte length.
                // CRITICAL FIX: lengthAdjustment should be 4 (for the 4-byte CRC after AVL data payload)
                if (pipeline.get("teltonikaAvlFrame") == null) {
                    pipeline.addBefore("protocolDetector", "teltonikaAvlFrame",
                            new LengthFieldBasedFrameDecoder(
                                    1024 * 1024, 4, 4, 4, 8, true // maxFrameLength, lengthFieldOffset, lengthFieldLength, lengthAdjustment, initialBytesToStrip, failFast
                            )
                    );
                    logger.info("Added teltonikaAvlFrame for Teltonika AVL data.");
                }
                break;
            case "GT06":
            case "TK103":
                // Both GT06 and TK103 use CRLF terminator
                if (pipeline.get("gt06Tk103Frame") == null) {
                    pipeline.addBefore("protocolDetector", "gt06Tk103Frame",
                            new DelimiterBasedFrameDecoder(
                                    1024, true, // 1024 bytes max frame length, strip delimiters
                                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A}) // CRLF delimiter
                            )
                    );
                    logger.info("Added gt06Tk103Frame for {} protocol.", protocol);
                }
                break;
            default:
                logger.warn("No specific framing configured for non-Teltonika protocol: {}. Removing ProtocolDetectionHandler.", protocol);
        }
        // Always remove this handler once framer is set for GT06/TK103 or if no specific framing is needed,
        // to prevent it from interfering with subsequent processing.
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
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            logger.info("Channel idle, closing connection");
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}