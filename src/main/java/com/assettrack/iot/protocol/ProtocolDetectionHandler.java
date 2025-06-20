package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects incoming protocol frames and, for Teltonika AVL data, installs
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
            // Log every raw buffer we see
            logger.info("ProtocolDetectionHandler: ENTER channelRead, raw hex = {}", ByteBufUtil.hexDump(buf));

            // Copy bytes for detection without changing readerIndex
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            // Run protocol detection
            ProtocolDetector.ProtocolDetectionResult result = detector.detect(data);
            if (result.isValid() && "TELTONIKA".equalsIgnoreCase(result.getProtocol())) {
                String packetType = result.getPacketType();
                logger.info("ProtocolDetectionHandler: detected {} packetType={}, version={}",
                        result.getProtocol(), packetType, result.getVersion());

                ChannelPipeline pipeline = ctx.pipeline();
                if ("IMEI".equalsIgnoreCase(packetType)) {
                    // IMEI frames are fixed-length; just pass them downstream
                    logger.info("ProtocolDetectionHandler: passing IMEI frame downstream");
                    ctx.fireChannelRead(buf.retain());
                } else {
                    // Any Teltonika data (e.g. AVL_DATA_CODEC_8) → install frame decoder
                    logger.info("ProtocolDetectionHandler: TELTONIKA DATA packet detected — installing frame decoder and removing self");
                    pipeline.addFirst("teltonikaFrameDecoder",
                            new LengthFieldBasedFrameDecoder(
                                    10240,  // maxFrameLength
                                    4,      // lengthFieldOffset (skip 4-byte preamble)
                                    4,      // lengthFieldLength
                                    0,      // lengthAdjustment
                                    0       // initialBytesToStrip
                            )
                    );
                    pipeline.remove(this);
                    // Re-fire the current buffer so the new framer sees it immediately
                    ctx.fireChannelRead(buf.retain());
                }
                return;
            }
        } finally {
            buf.release();
        }
        // Not Teltonika or invalid detection → let other handlers see it
        super.channelRead(ctx, msg);
    }
}
