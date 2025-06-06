package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import jakarta.annotation.PostConstruct;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@ChannelHandler.Sharable
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    private final ProtocolDetector protocolDetector;

    @Autowired
    public ProtocolDetectionHandler(ProtocolDetector protocolDetector) {
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
            buf.getBytes(buf.readerIndex(), data); // Don't consume buffer

            // Special handling for Teltonika IMEI packets
            if (isTeltonikaImeiPacket(data)) {
                String imei = new String(data, 2, data.length-2, StandardCharsets.US_ASCII);
                imei = imei.replaceAll("[^0-9]", "").substring(0, 15);

                DeviceMessage message = new DeviceMessage();
                message.setProtocol("TELTONIKA");
                message.setMessageType("IMEI");
                message.setImei(imei);

                // Send Teltonika login response (0x01)
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01}));
                logger.info("Accepted Teltonika IMEI: {}", imei);

                ctx.fireChannelRead(message);
                return;
            }

            logger.info("Detecting protocol for packet: {}", Hex.encodeHexString(data));
            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

            if (result != null) {
                logger.info("Detected protocol: {} - {}", result.getProtocol(), result.getPacketType());
                ctx.fireChannelRead(result); // Forward detection result
            }

            // Always forward the original message
            ctx.fireChannelRead(msg);
        } catch (Exception e) {
            logger.error("Protocol detection error", e);
            ctx.close();
        }
    }

    private boolean isTeltonikaImeiPacket(byte[] data) {
        if (data == null || data.length < 17) return false;

        // Check for Teltonika IMEI packet structure (00 0F followed by 15 digits)
        if (data[0] == 0x00 && data[1] == 0x0F && data.length == 17) {
            try {
                String imei = new String(data, 2, 15, StandardCharsets.US_ASCII);
                return imei.matches("^\\d{15}$");
            } catch (Exception e) {
                return false;
            }
        }
        return false;
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

    @PostConstruct
    public void logInstance() {
        logger.info("[INIT] ProtocolDetectionHandler initialized with instance ID: {}", System.identityHashCode(this));
    }


}