package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects incoming protocol frames and, for Teltonika AVL data and GT06, installs
 * a LengthFieldBasedFrameDecoder before routing the raw bytes down the pipeline.
 */
@Component
@Sharable
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector detector = new ProtocolDetector();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.channelRead(ctx, msg);
            return;
        }
        try {
            logger.info("ProtocolDetectionHandler: ENTER channelRead, raw hex = {}", ByteBufUtil.hexDump(buf));

            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            ProtocolDetector.ProtocolDetectionResult result = detector.detect(data);

            if (result.isDetected()) {
                ChannelPipeline pipeline = ctx.pipeline();

                if ("TELTONIKA".equalsIgnoreCase(result.getProtocol()) && result.getPacketType().startsWith("AVL_DATA_CODEC_")) {
                    logger.info("ProtocolDetectionHandler: TELTONIKA DATA packet detected — installing frame decoder and removing self");
                    pipeline.addFirst("teltonikaFrameDecoder",
                            new LengthFieldBasedFrameDecoder(
                                    10240,  // maxFrameLength
                                    4,      // lengthFieldOffset
                                    4,      // lengthFieldLength
                                    2,      // lengthAdjustment
                                    8       // initialBytesToStrip
                            )
                    );
                    pipeline.remove(this);
                    ctx.fireChannelRead(buf.retain());
                } else if ("GT06".equalsIgnoreCase(result.getProtocol())) {
                    logger.info("ProtocolDetectionHandler: GT06 packet detected — installing frame decoder and removing self");
                    pipeline.addFirst("gt06FrameDecoder",
                            new LengthFieldBasedFrameDecoder(
                                    10240, // maxFrameLength
                                    2,     // lengthFieldOffset (after 78 78)
                                    1,     // lengthFieldLength (the 1-byte length field)
                                    2,     // lengthAdjustment (add 2 bytes for the 0D 0A stop bit)
                                    0      // initialBytesToStrip (keep 78 78 for Gt06Handler)
                            )
                    );
                    pipeline.remove(this);
                    ctx.fireChannelRead(buf.retain());
                } else {
                    logger.info("Detected {} protocol (PacketType: {}): {}", result.getProtocol(), result.getPacketType(), ByteBufUtil.hexDump(data));
                    ctx.channel().attr(ProtocolDetector.PROTOCOL_DETECTION_RESULT_KEY).set(result);
                    ctx.fireChannelRead(buf.retain());
                }
            } else {
                logger.warn("ProtocolDetectionHandler: No protocol detected for incoming data. Error: {}. Releasing buffer.", result.getError());
                ReferenceCountUtil.release(buf);
            }
        } finally {
            // No general release here, as handled paths explicitly retain, and unhandled paths release.
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ProtocolDetectionHandler: Channel error", cause);
        ctx.close();
    }
}