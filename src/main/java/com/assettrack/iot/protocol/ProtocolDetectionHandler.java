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
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Dynamically detects protocol (Teltonika, GT06, TK103) and inserts appropriate framers.
 */
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

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
            // If the message is not a ByteBuf, pass it to the next handler
            ctx.fireChannelRead(msg);
            return;
        }

        // Copy buffer content to byte array and then release the ByteBuf
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        ReferenceCountUtil.release(buf); // Release the ByteBuf as its content has been consumed

        String hexData = Hex.encodeHexString(data);
        logger.info("Protocol detection for packet: {}", hexData);

        ProtocolDetector.ProtocolDetectionResult result;
        try {
            result = protocolDetector.detect(data);
        } catch (Exception e) {
            logger.error("Error during protocol detection", e);
            result = ProtocolDetector.ProtocolDetectionResult.failure("DETECTION_ERROR");
        }

        String protocol = null;
        String packetType = null;

        if (result != null && result.isDetected()) {
            protocol = result.getProtocol();
            packetType = result.getPacketType();
            logger.info("Detected {} protocol: {}, Packet Type: {}", protocol, packetType);

            if ("TELTONIKA".equals(protocol)) {
                if ("IMEI".equals(packetType)) {
                    // For IMEI, add short framer if not already present.
                    // Keep this handler in pipeline to process subsequent AVL data.
                    if (ctx.pipeline().get("teltonikaShortFrame") == null) {
                        ctx.pipeline().addBefore("protocolDetector", "teltonikaShortFrame",
                                new LengthFieldBasedFrameDecoder(64, 0, 2, 0, 2, true));
                        logger.info("Added teltonikaShortFrame for IMEI packet.");
                    }
                    teltonikaImeiHandled = true; // Mark IMEI as handled
                    // Delegate handling of the IMEI packet to TeltonikaHandler
                    teltonikaHandler.handle(data, ctx);
                } else if (teltonikaImeiHandled && (packetType.startsWith("CODEC") || "UNKNOWN_TELTONIKA_CODEC".equals(packetType))) {
                    // If IMEI was handled and now an AVL-like packet is detected, switch to AVL framer.
                    ChannelPipeline pipeline = ctx.pipeline();
                    if (pipeline.get("teltonikaShortFrame") != null) {
                        pipeline.remove("teltonikaShortFrame");
                        logger.info("Removed teltonikaShortFrame after detecting AVL packet post-IMEI.");
                    }
                    if (pipeline.get("teltonikaAvlFrame") == null) {
                        // Add the AVL framer for Teltonika AVL data
                        pipeline.addBefore("protocolDetector", "teltonikaAvlFrame",
                                new LengthFieldBasedFrameDecoder(1024 * 1024, 4, 4, 0, 8, true));
                        logger.info("Added teltonikaAvlFrame for Teltonika AVL data.");
                    }
                    // Delegate handling of the AVL data packet to TeltonikaHandler
                    teltonikaHandler.handle(data, ctx);
                } else {
                    logger.warn("Received Teltonika packet (Type: {}) without prior IMEI handshake or unhandled after IMEI. Closing channel.", packetType);
                    ctx.close();
                }
            } else if ("GT06".equals(protocol) || "TK103".equals(protocol)) {
                setupFramingAndRemoveSelf(ctx.pipeline(), protocol);
                // For GT06/TK103, pass the message to the next handler in the pipeline
                ctx.fireChannelRead(Unpooled.wrappedBuffer(data));
            } else {
                logger.error("No protocol detected for data: {}", hexData);
                result = ProtocolDetector.ProtocolDetectionResult.failure("UNDETECTED_PROTOCOL");
                ctx.close();
            }
        } else {
            logger.error("No protocol detected for data: {}", hexData);
            result = ProtocolDetector.ProtocolDetectionResult.failure("UNDETECTED_PROTOCOL");
            ctx.close();
        }

        if (result != null && !result.isDetected()) {
            logger.warn("Received undetected protocol result: {}"//, result.getReason()
            );
        }
    }

    private void setupFramingAndRemoveSelf(ChannelPipeline pipeline, String protocol) {
        switch (protocol) {
            case "GT06":
            case "TK103":
                // Both GT06 and TK103 use CRLF terminator
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
                logger.warn("No specific framing configured for non-Teltonika protocol: {}", protocol);
        }
        // For non-Teltonika protocols, remove this handler once framer is set.
        pipeline.remove(this);
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