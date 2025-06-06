package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil; // Import for releasing ByteBuf
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

@ChannelHandler.Sharable
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);
    private final ProtocolDetector protocolDetector;

    public ProtocolDetectionHandler(ProtocolDetector protocolDetector) {
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // If the message is not a ByteBuf, pass it along immediately.
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data); // Read data without consuming (increasing readerIndex)

            // Check if it's a Teltonika IMEI packet (handshake)
            if (isTeltonikaImeiPacket(data)) {
                String imei = new String(data, 2, data.length - 2, StandardCharsets.US_ASCII)
                        .replaceAll("[^0-9]", "")
                        .substring(0, 15);

                // Acknowledge Teltonika IMEI packet
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01}));
                logger.info("Accepted Teltonika IMEI: {}", imei);

                // Release the ByteBuf as this handler has fully processed the IMEI handshake
                ReferenceCountUtil.release(buf);
                return; // Stop further processing of this IMEI packet in the pipeline
            } else {
                // For all other ByteBufs (assumed to be data packets),
                // simply pass them downstream to the next handler (GenericProtocolDecoder).
                // The GenericProtocolDecoder will then be responsible for protocol detection and actual decoding.
                logger.info("Passing raw data to next decoder: {}", Hex.encodeHexString(data));
                ctx.fireChannelRead(msg);
            }
        } catch (Exception e) {
            logger.error("Protocol detection error", e);
            // Ensure the ByteBuf is released if an error occurs
            ReferenceCountUtil.release(buf);
            ctx.close();
        }
    }

    private boolean isTeltonikaImeiPacket(byte[] data) {
        if (data == null || data.length < 17) return false;
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
}