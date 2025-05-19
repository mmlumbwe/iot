package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector protocolDetector;


    @Autowired
    public ProtocolDetectionHandler(ProtocolDetector protocolDetector) {
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg == null) {
            ctx.fireChannelRead(msg); // Pass null along the pipeline
            return;
        }

        if (!(msg instanceof ByteBuf)) {
            // Not a ByteBuf, pass to next handler
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        try {
            if (buf.readableBytes() > 0) {
                byte[] data = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), data);

                if (data.length < ProtocolDetector.MIN_DATA_LENGTH) {
                    logger.debug("Received data too short: {} bytes", data.length);
                    return;
                }

                ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);
                ctx.fireChannelRead(result);
            }
            ctx.fireChannelRead(msg);
        } finally {
            buf.release(); // Ensure ByteBuf is released
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Protocol detection error", cause);
        ctx.close();
    }
}