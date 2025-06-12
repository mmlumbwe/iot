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

/**
 * Dynamically detects protocol (Teltonika, GT06, TK103) and inserts appropriate framers.
 */
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    private final ProtocolDetector protocolDetector;
    private final TeltonikaHandler teltonikaHandler;
    private final Gt06Handler gt06Handler;

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
        buf.retain();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        String hexData = Hex.encodeHexString(data);
        logger.info("Protocol detection for packet: {}", hexData);

        ProtocolDetector.ProtocolDetectionResult result;
        try {
            result = protocolDetector.detect(data);
        } catch (Exception e) {
            logger.error("Error during detect()", e);
            result = null;
        }

        String protocol = null;
        if (result != null && result.isDetected()) {
            protocol = result.getProtocol();
            logger.info("Detected {} protocol: {}", protocol, result.getPacketType());
            ctx.fireChannelRead(result);
        } else {
            protocol = fallbackDetect(ctx, data);
            if (protocol == null) {
                // buffered data released in fallbackDetect
                return;
            }
        }

        // Insert frame decoder based on detected protocol
        setupFraming(ctx.pipeline(), protocol);
        // Remove ourselves — framing is set up for subsequent messages
        ctx.pipeline().remove(this);

        // Forward original buffer through new framers
        ctx.fireChannelRead(buf);
    }

    private String fallbackDetect(ChannelHandlerContext ctx, byte[] data) {
        String hexFallback = Hex.encodeHexString(data);
        // Teltonika
        if (teltonikaHandler != null && new ProtocolDetector.TeltonikaMatcher().matches(data)) {
            String type = new ProtocolDetector.TeltonikaMatcher().getPacketType(data);
            logger.info("Fallback Teltonika detected: {}", type);
            ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.success(
                    "TELTONIKA", type, ProtocolDetector.VERSION));
            return "TELTONIKA";
        }
        // GT06
        if (gt06Handler != null && new ProtocolDetector.Gt06Matcher().matches(data)) {
            String type = new ProtocolDetector.Gt06Matcher().getPacketType(data);
            logger.info("Fallback GT06 detected: {}", type);
            ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.success(
                    "GT06", type, ProtocolDetector.VERSION));
            return "GT06";
        }
        // TK103
        ProtocolDetector.Tk103Matcher tk103 = new ProtocolDetector.Tk103Matcher();
        if (tk103.matches(data)) {
            String type = tk103.getPacketType(data);
            logger.info("Fallback TK103 detected: {}", type);
            ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.success(
                    "TK103", type, ProtocolDetector.VERSION));
            return "TK103";
        }
        // Unknown
        logger.error("No protocol detected for data: {}", hexFallback);
        ctx.fireChannelRead(ProtocolDetector.ProtocolDetectionResult.failure("DETECTION_ERROR"));
        return null;
    }

    private void setupFraming(ChannelPipeline pipeline, String protocol) {
        switch (protocol) {
            case "TELTONIKA":
                // AVL data: skip 4-byte preamble, then 4-byte length
                // This should be added FIRST to handle larger data packets
                pipeline.addBefore("protocolDetector", "teltonikaAvlFrame",
                        new LengthFieldBasedFrameDecoder(
                                1024 * 1024, 4, 4, 0, 8, true
                        )
                );
                // IMEI: 2-byte length field
                // This should be added AFTER the AVL frame decoder
                pipeline.addBefore("protocolDetector", "teltonikaShortFrame",
                        new LengthFieldBasedFrameDecoder(
                                64, 0, 2, 0, 2, true
                        )
                );
                break;
            case "GT06":
            case "TK103":
                // Both GT06 and TK103 use CRLF terminator
                pipeline.addBefore("protocolDetector", "gt06Tk103Frame",
                        new DelimiterBasedFrameDecoder(
                                1024, true,
                                Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                        )
                );
                break;
            default:
                // Should not occur, but log if it does
                logger.warn("No framing configured for protocol: {}", protocol);
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