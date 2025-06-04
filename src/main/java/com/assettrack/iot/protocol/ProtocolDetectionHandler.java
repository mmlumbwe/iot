package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    public static final AttributeKey<String> PROTOCOL_ATTR =
            AttributeKey.valueOf("protocol");

    private final ProtocolDetector protocolDetector;

    @Autowired
    public ProtocolDetectionHandler(ProtocolDetector protocolDetector) {
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            if (!buf.isReadable()) return;

            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            // Detect protocol
            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);
            if (result != null && result.isValid()) {
                String protocol = result.getProtocol();
                logger.info("Detected protocol: {}", protocol);

                // Store protocol as attribute for routing
                ctx.channel().attr(PROTOCOL_ATTR).set(protocol);
            } else {
                logger.warn("Unable to detect protocol, closing connection");
                ctx.close();
                return;
            }

            // Forward original message to next handler
            ctx.fireChannelRead(msg);

        } catch (Exception e) {
            logger.error("Protocol detection failed", e);
            ctx.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            logger.info("Channel idle, closing connection");
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in protocol detection", cause);
        ctx.close();
    }
}
