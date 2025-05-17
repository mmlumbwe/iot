package com.assettrack.iot.protocol;

import com.assettrack.iot.session.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class BaseProtocolDecoder extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BaseProtocolDecoder.class);

    private final ProtocolDetector protocolDetector;
    private final SessionManager sessionManager;

    @Autowired
    public BaseProtocolDecoder(SessionManager sessionManager, ProtocolDetector protocolDetector) {
        this.sessionManager = sessionManager;
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);
            logDetectionResult(result, data);

            if (result.isValid()) {
                ctx.channel().attr(ProtocolConstants.PROTOCOL_ATTRIBUTE).set(result.getProtocol());
                Object decoded = decode(ctx, buf, result);
                if (decoded != null) {
                    ctx.fireChannelRead(decoded);
                }
            } else {
                logger.warn("Invalid protocol detection: {}", result.getError());
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            logger.error("Error during protocol detection", e);
            ReferenceCountUtil.release(msg);
        } finally {
            buf.release();
        }
    }

    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf,
                            ProtocolDetector.ProtocolDetectionResult result) {
        // Default implementation - protocol specific decoders should override
        logger.debug("No specific decoder for protocol: {}", result.getProtocol());
        return null;
    }

    private void logDetectionResult(ProtocolDetector.ProtocolDetectionResult result, byte[] data) {
        if (logger.isDebugEnabled()) {
            logger.debug("Detected protocol: {}, Type: {}, Version: {}",
                    result.getProtocol(), result.getPacketType(), result.getVersion());
            logger.debug("Raw data ({} bytes): {}", data.length, bytesToHex(data));
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}