package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
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
                logger.info("Received null message");
                return;
            }

            if (!(msg instanceof ByteBuf)) {
                logger.info("Forwarding non-ByteBuf message of type {}", msg.getClass().getSimpleName());
                ctx.fireChannelRead(msg);
                return;
            }

            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                logger.info("Received empty ByteBuf");
                return;
            }

            // Extract data for processing
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            logger.info("Processing {} bytes of data: {}", data.length, Hex.encodeHexString(data));

            // Perform protocol detection
            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);
            if (result != null) {
                logger.info("Detected protocol: {}", result.getProtocol());
                ctx.fireChannelRead(result);
            } else {
                logger.info("No protocol detected for incoming data");
            }
        } catch (Exception e) {
            logger.error("Error during message processing", e);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel error", cause);
        ctx.close();
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
}