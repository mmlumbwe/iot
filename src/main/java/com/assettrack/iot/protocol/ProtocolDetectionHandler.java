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
            logger.info("Received message of type: {}", msg != null ? msg.getClass().getName() : "null");

            if (msg == null) {
                logger.warn("Null message received");
                return;
            }

            if (!(msg instanceof ByteBuf)) {
                logger.warn("Unexpected message type: {}", msg.getClass().getName());
                ctx.fireChannelRead(msg);
                return;
            }

            ByteBuf buf = (ByteBuf) msg;
            logger.info("ByteBuf details - readable: {}, readerIndex: {}, writerIndex: {}",
                    buf.isReadable(), buf.readerIndex(), buf.writerIndex());

            if (!buf.isReadable()) {
                logger.warn("Empty ByteBuf received");
                buf.release();
                return;
            }

            // Extract data
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            logger.info("Attempting protocol detection on: {}", Hex.encodeHexString(data));

            // THIS IS THE LINE THAT'S NOT BEING REACHED
            //ProtocolDetector.ProtocolDetectionResult result1 = protocolDetector.detect(data);
            //logger.info("Detection result: {}", result1 != null ? result1.getProtocol() : "null");

            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.debugDetection(data);
            if (result != null) {
                ctx.fireChannelRead(result);
            }

            if (result != null) {
                ctx.fireChannelRead(result);
            }
            ctx.fireChannelRead(msg);
        } catch (Exception e) {
            logger.error("Error during protocol detection", e);
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