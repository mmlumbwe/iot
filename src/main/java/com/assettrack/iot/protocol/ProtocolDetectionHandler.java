package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
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
        try {
            if (msg == null) {
                logger.info("Received null message, skipping processing");
                return;
            }

            if (!(msg instanceof ByteBuf)) {
                logger.info("Received non-ByteBuf message of type {}, forwarding", msg.getClass().getName());
                ctx.fireChannelRead(msg);
                return;
            }

            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                logger.info("Received empty ByteBuf, skipping processing");
                return;
            }

            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);
            if (result != null) {
                ctx.fireChannelRead(result);
            }
        } catch (Exception e) {
            logger.error("Error during protocol detection", e);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Protocol detection pipeline error", cause);
        ctx.close();
    }
}