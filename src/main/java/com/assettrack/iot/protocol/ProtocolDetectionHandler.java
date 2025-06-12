package com.assettrack.iot.protocol;

import com.assettrack.iot.protocol.TeltonikaConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.assettrack.iot.protocol.BaseProtocolDecoder.PROTOCOL_HEADER_1;
import static com.assettrack.iot.protocol.BaseProtocolDecoder.PROTOCOL_HEADER_2;

public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
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

        // If detection failed, try fallbacks (omitted for brevity)...

        // Primary detection succeeded
        if (result != null && result.isDetected()) {
            logger.info("Detected {} protocol: {}", result.getProtocol(), result.getPacketType());

            // ** Teltonika special handling: swap out the CRLF decoder for a length-field decoder **
            if ("TELTONIKA".equals(result.getProtocol())) {
                ChannelPipeline pipeline = ctx.pipeline();

                // 1) Remove the existing CRLF‐based frameDecoder
                if (pipeline.context("frameDecoder") != null) {
                    pipeline.remove("frameDecoder");
                    logger.info("Removed CRLF frameDecoder for Teltonika protocol");
                }

                // 2) Add a LengthFieldBasedFrameDecoder sized to header + MAX_DATA_LENGTH
                if (pipeline.context("teltonikaFrameDecoder") == null) {
                    int maxFrame = TeltonikaConstants.HEADER_SIZE + TeltonikaConstants.MAX_DATA_LENGTH;
                    pipeline.addFirst("teltonikaFrameDecoder",
                            new LengthFieldBasedFrameDecoder(
                                    maxFrame,    // max bytes in a single packet
                                    4,           // lengthFieldOffset: skip the 4-byte preamble
                                    4,           // lengthFieldLength: next 4 bytes is dataLength
                                    0,           // lengthAdjustment: no extra adjustment
                                    0            // initialBytesToStrip: keep header in the frame
                            )
                    );
                    logger.info("Installed Teltonika LengthFieldBasedFrameDecoder (maxFrameLength={})", maxFrame);
                }
            }

            // Propagate detection and the raw buffer onward
            ctx.fireChannelRead(result);
            ctx.fireChannelRead(buf);
            return;
        }

        // … fallback logic here …

        // If nothing matched
        ReferenceCountUtil.release(buf);
    }
}
