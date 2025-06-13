package com.assettrack.iot.protocol;

import com.assettrack.iot.protocol.ProtocolDetector.ProtocolDetectionResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    /**
     * Attribute on each channel indicating whether we've already installed
     * the Teltonika AVL frame decoder.
     */
    private static final AttributeKey<Boolean> TELTONIKA_AVL_ADDED =
            AttributeKey.valueOf("TELTONIKA_AVL_ADDED");

    /** The name under which this handler is added to the pipeline */
    public static final String NAME = "protocolDetector";

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

        // Only intercept raw ByteBufs
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // If we don't yet have at least 8 bytes (preamble + length field), skip detection
        if (buf.readableBytes() < 8) {
            ctx.fireChannelRead(buf);
            return;
        }

        // Retain for downstream handlers
        buf.retain();
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            ProtocolDetectionResult result = protocolDetector.detect(data);
            if (result.isSuccess()) {
                String protocol = result.getProtocol();
                String packetType = result.getPacketType();
                ChannelPipeline pipeline = ctx.pipeline();

                // === TELTONIKA ===
                if ("TELTONIKA".equalsIgnoreCase(protocol)) {

                    // 1) IMEI handshake 
                    if ("IMEI".equalsIgnoreCase(packetType)) {
                        // only send the login reply once per channel
                        if (ctx.channel().attr(TELTONIKA_AVL_ADDED).get() == null) {
                            byte[] resp = teltonikaHandler.generateResponse(null);
                            ctx.writeAndFlush(Unpooled.wrappedBuffer(resp));
                            logger.info("Sent IMEI login response for Teltonika device");
                        }
                        // mark as not yet added AVL decoder
                        ctx.channel().attr(TELTONIKA_AVL_ADDED).set(false);

                        // propagate detection result + raw bytes
                        ctx.fireChannelRead(result);
                        ctx.fireChannelRead(buf.retain());
                        return;
                    }

                    // 2) AVL data packet → install length‐field decoder once
                    Boolean added = ctx.channel().attr(TELTONIKA_AVL_ADDED).get();
                    if (!Boolean.TRUE.equals(added)) {
                        pipeline.addBefore(
                                "decoder",
                                "teltonikaAvlFrame",
                                new LengthFieldBasedFrameDecoder(
                                        1024 * 1024,   // max frame length = 1 MB
                                        4,             // lengthFieldOffset
                                        4,             // lengthFieldLength
                                        4,             // lengthAdjustment
                                        8,             // initialBytesToStrip
                                        true           // failFast
                                )
                        );
                        ctx.channel().attr(TELTONIKA_AVL_ADDED).set(true);
                        pipeline.remove(this);
                        logger.info("Installed Teltonika AVL frame decoder and removed ProtocolDetectionHandler");
                    }

                    ctx.fireChannelRead(result);
                    ctx.fireChannelRead(buf.retain());
                    return;
                }

                // === GT06 / TK103 ===
                if ("GT06".equalsIgnoreCase(protocol) || "TK103".equalsIgnoreCase(protocol)) {
                    if (pipeline.get("gt06Tk103Frame") == null) {
                        pipeline.addBefore(
                                NAME,
                                "gt06Tk103Frame",
                                new DelimiterBasedFrameDecoder(
                                        1024,
                                        true,
                                        Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                                )
                        );
                        logger.info("Added GT06/TK103 delimiter frame decoder");
                    }
                    pipeline.remove(this);
                    ctx.fireChannelRead(result);
                    ctx.fireChannelRead(buf.retain());
                    return;
                }

                // === Unknown protocol ===
                pipeline.remove(this);
                logger.warn("Unknown protocol '{}', removing ProtocolDetectionHandler", protocol);
                ctx.fireChannelRead(result);
                ctx.fireChannelRead(buf.retain());
                return;
            }

            // Detection failed → pass through untouched
            ctx.fireChannelRead(buf);
        } finally {
            // release our retain
            buf.release();
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
            logger.info("ProtocolDetectionHandler: Channel idle, closing connection");
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }
}
