package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // Copy incoming bytes for detection
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        ReferenceCountUtil.retain(msg);

        try {
            String detected = ctx.channel().attr(DETECTED_PROTOCOL_KEY).get();
            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

            if (detected == null) {
                // First-time detection
                if (result.isValid() && "TELTONIKA".equals(result.getProtocol())) {
                    String type = result.getPacketType();
                    logger.info("Detected TELTONIKA protocol: {}, Packet Type: {}", result.getVersion(), type);
                    ctx.channel().attr(DETECTED_PROTOCOL_KEY).set("TELTONIKA");
                    ChannelPipeline pipeline = ctx.pipeline();

                    if (!teltonikaImeiHandled && "IMEI".equals(type)) {
                        // 1) Add IMEI framer after detection, so detection always sees raw bytes first
                        pipeline.addAfter("protocolDetector", "teltonikaImeiFrame",
                                new LengthFieldBasedFrameDecoder(
                                        64,  // small max for the 17-byte IMEI packet
                                        0, 2, 0, 2, true
                                )
                        );
                        logger.info("Added teltonikaImeiFrame for Teltonika IMEI.");

                        // Send ACK to complete handshake
                        ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01}));
                        logger.info("Sent login request (0x01) to device: {}",
                                Hex.encodeHexString(data).substring(4));
                        teltonikaImeiHandled = true;
                        return;
                    }
                }

                if (result.isValid() && ("GT06".equals(result.getProtocol()) ||
                        "TK103".equals(result.getProtocol()))) {
                    // 2) GT06/TK103 framing
                    String proto = result.getProtocol();
                    ChannelPipeline pipeline = ctx.pipeline();
                    pipeline.addAfter("protocolDetector", "gt06Tk103Frame",
                            new DelimiterBasedFrameDecoder(
                                    1024, true,
                                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                            )
                    );
                    logger.info("Added gt06Tk103Frame for {} protocol.", proto);
                    pipeline.remove(this);
                    ctx.fireChannelRead(msg);
                    return;
                }

                if (!result.isValid()) {
                    logger.warn("Unknown protocol: {}. Closing channel.", result.getProtocol());
                    ctx.close();
                    return;
                }
            }

            // If Teltonika IMEI handshake done and now receiving data packets
            if ("TELTONIKA".equals(detected) && teltonikaImeiHandled) {
                ProtocolDetector.ProtocolDetectionResult second = result;
                String type = second.getPacketType();
                if (!"IMEI".equals(type)) {
                    ChannelPipeline pipeline = ctx.pipeline();
                    // Replace IMEI framer with AVL framer
                    if (pipeline.get("teltonikaImeiFrame") != null) {
                        pipeline.replace("teltonikaImeiFrame", "teltonikaAvlFrame",
                                new LengthFieldBasedFrameDecoder(
                                        1024 * 1024, // 1 MB max
                                        4, 4, 4, 8, true
                                )
                        );
                    } else if (pipeline.get("teltonikaAvlFrame") == null) {
                        pipeline.addAfter("protocolDetector", "teltonikaAvlFrame",
                                new LengthFieldBasedFrameDecoder(
                                        1024 * 1024,
                                        4, 4, 4, 8, true
                                )
                        );
                    }
                    logger.info("Added teltonikaAvlFrame for Teltonika AVL data.");
                    pipeline.remove(this);
                    ctx.fireChannelRead(msg);
                    return;
                }
            }

            // Pass through if framing is already configured
            ctx.fireChannelRead(msg);

        } finally {
            // Netty will release when downstream handlers consume
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ProtocolDetectionHandler: Channel error", cause);
        ctx.close();
    }
}
