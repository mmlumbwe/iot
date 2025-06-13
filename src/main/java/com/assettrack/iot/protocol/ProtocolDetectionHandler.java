package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    // Marks that we've installed the Teltonika AVL frame decoder for this channel
    private static final AttributeKey<Boolean> TELTONIKA_AVL_ADDED =
            AttributeKey.valueOf("TELTONIKA_AVL_ADDED");

    // Pipeline name for detection handler
    public static final String NAME = "protocolDetector";

    private final ProtocolDetector protocolDetector;
    private final Gt06Handler gt06Handler;

    public ProtocolDetectionHandler(ProtocolDetector protocolDetector,
                                    Gt06Handler gt06Handler) {
        this.protocolDetector = protocolDetector;
        this.gt06Handler = gt06Handler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // We only need the first 2 bytes to detect IMEI login
        if (buf.readableBytes() < 2) {
            ctx.fireChannelRead(buf);
            return;
        }

        buf.retain();
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);

            var result = protocolDetector.detect(data);
            if (result.isSuccess()) {
                String protocol = result.getProtocol();
                String packetType = result.getPacketType();
                ChannelPipeline pipeline = ctx.pipeline();

                // ---- TELTONIKA handshake ----
                if ("TELTONIKA".equalsIgnoreCase(protocol)) {

                    // IMEI login
                    if ("IMEI".equalsIgnoreCase(packetType)
                            && ctx.channel().attr(TELTONIKA_AVL_ADDED).get() == null) {

                        // 1) send proper login ACK
                        byte[] loginAck = generateLoginResponse((short) 0);
                        ctx.writeAndFlush(Unpooled.wrappedBuffer(loginAck));
                        logger.info("Sent Teltonika login ACK");

                        // 2) install length-frame decoder for AVL packets
                        pipeline.addBefore(
                                "decoder",
                                "teltonikaFrame",
                                new LengthFieldBasedFrameDecoder(
                                        1024 * 1024,  // max frame size
                                        4,            // length field offset
                                        4,            // length field length
                                        0,            // lengthAdjustment
                                        4,            // initialBytesToStrip (skip header+length)
                                        true          // failFast
                                )
                        );
                        ctx.channel().attr(TELTONIKA_AVL_ADDED).set(true);

                        // 3) remove this detector (we no longer need it)
                        pipeline.remove(this);

                        // 4) forward detection result + raw buffer downstream
                        ctx.fireChannelRead(result);
                        ctx.fireChannelRead(buf.retain());
                        return;
                    }

                    // Any other Teltonika packet before framing just pass through
                    ctx.fireChannelRead(result);
                    ctx.fireChannelRead(buf.retain());
                    return;
                }

                // ---- GT06 / TK103 ----
                if ("GT06".equalsIgnoreCase(protocol) || "TK103".equalsIgnoreCase(protocol)) {
                    if (pipeline.get("gt06Frame") == null) {
                        pipeline.addBefore(
                                NAME,
                                "gt06Frame",
                                new DelimiterBasedFrameDecoder(
                                        1024,
                                        true,
                                        Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A})
                                )
                        );
                        logger.info("Installed GT06/TK103 frame decoder");
                    }
                    pipeline.remove(this);
                    ctx.fireChannelRead(result);
                    ctx.fireChannelRead(buf.retain());
                    return;
                }

                // ---- Unknown or unsupported ----
                pipeline.remove(this);
                logger.warn("Unknown protocol '{}' — removed detection handler", protocol);
                ctx.fireChannelRead(buf);
                return;
            }

            // Detection failed → just pass the raw buffer
            ctx.fireChannelRead(buf);

        } finally {
            buf.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ProtocolDetectionHandler error", cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            logger.info("Channel idle, closing connection");
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * Build a Teltonika login ACK packet: 0x78 0x78 0x05 0x01 0x00 0x00 0x01 CRC(2) 0x0D 0x0A
     */
    private byte[] generateLoginResponse(short serial) {
        byte[] resp = new byte[11];
        resp[0] = 0x78;
        resp[1] = 0x78;
        resp[2] = 0x05;          // length
        resp[3] = 0x01;          // protocol = login
        resp[4] = (byte) (serial >> 8);
        resp[5] = (byte) (serial & 0xFF);
        resp[6] = 0x01;          // status = OK
        // CRC16 (bytes 2..6)
        var crcBuf = ByteBuffer.wrap(resp, 2, 5);
        int crc = Checksum.crc16(Checksum.CRC16_X25, crcBuf);
        resp[7] = (byte) (crc >> 8);
        resp[8] = (byte) crc;
        resp[9] = 0x0D;
        resp[10] = 0x0A;
        return resp;
    }
}
