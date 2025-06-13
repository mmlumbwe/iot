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

        // Retain the buffer for detection
        buf.retain();
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

            if (result.isSuccess()) {
                String protocol = result.getProtocol();
                String packetType = result.getPacketType();

                if ("TELTONIKA".equalsIgnoreCase(protocol)) {
                    if ("IMEI".equalsIgnoreCase(packetType)) {
                        // Handle IMEI packet directly without framing
                        if (!teltonikaImeiHandled) {
                            teltonikaImeiHandled = true;
                            byte[] imeiResponse = teltonikaHandler.generateResponse(null);
                            ctx.writeAndFlush(Unpooled.wrappedBuffer(imeiResponse));
                            logger.info("Sent IMEI response for Teltonika IMEI packet. IMEI: {}",
                                    new String(data, 2, 15, StandardCharsets.US_ASCII));
                        }

                        // Pass the detection result and original buffer downstream
                        ctx.fireChannelRead(result);
                        ctx.fireChannelRead(buf.retain());
                        return;
                    } else {
                        // For AVL data, add the appropriate frame decoder
                        ChannelPipeline pipeline = ctx.pipeline();
                        if (pipeline.get("teltonikaAvlFrame") == null) {
                            pipeline.addBefore("decoder", "teltonikaAvlFrame",
                                    new LengthFieldBasedFrameDecoder(
                                            1024 * 1024,
                                            4, 4, 4, 8, true
                                    ));
                        }
                    }
                } else {
                    // Handle other protocols (GT06/TK103)
                    setupFramingAndRemoveSelf(ctx.pipeline(), protocol);
                }

                // Pass the detection result and original buffer downstream
                ctx.fireChannelRead(result);
                ctx.fireChannelRead(buf.retain());
                return;
            }

            // Fallback detection and error handling
            // ... existing fallback code ...
        } finally {
            buf.release();
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