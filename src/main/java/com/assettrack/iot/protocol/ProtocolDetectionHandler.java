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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    // Pipeline name for this handler in the pipeline
    public static final String NAME = "protocolDetector";

    private final ProtocolDetector protocolDetector;
    private final Gt06Handler gt06Handler;

    // Tracks whether we've completed the Teltonika IMEI handshake on this channel
    private boolean teltonikaImeiHandled = false;

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

        // Require at least 2 bytes to detect the protocol header
        if (buf.readableBytes() < 2) {
            ctx.fireChannelRead(buf);
            return;
        }

        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        var result = protocolDetector.detect(data);

        if (result.isSuccess()) {
            String protocol = result.getProtocol();
            String packetType = result.getPacketType();
            ChannelPipeline pipeline = ctx.pipeline();

            // === TELTONIKA ===
            if ("TELTONIKA".equalsIgnoreCase(protocol)) {

                // 1) IMEI handshake: ACK and swallow
                if ("IMEI".equalsIgnoreCase(packetType) && !teltonikaImeiHandled) {
                    byte[] ack = generateLoginResponse((short) 1);
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(ack));
                    logger.info("Sent Teltonika login ACK");

                    teltonikaImeiHandled = true;
                    buf.release();
                    return;
                }

                // 2) First AVL packet: install frame decoder, remove self, then forward
                if (teltonikaImeiHandled && !"IMEI".equalsIgnoreCase(packetType)) {
                    pipeline.addBefore(
                            "decoder",
                            "teltonikaFrame",
                            new LengthFieldBasedFrameDecoder(
                                    1024 * 1024, // max frame length = 1 MB
                                    4,           // lengthFieldOffset
                                    4,           // lengthFieldLength
                                    0,           // lengthAdjustment
                                    0,           // initialBytesToStrip
                                    true         // failFast
                            )
                    );
                    pipeline.remove(this);

                    // 2a) store detection result for the next decoder
                    ctx.fireChannelRead(result);
                    // 2b) forward the buffer for framing
                    ctx.fireChannelRead(buf.retain());
                    return;
                }

                // 3) Any other Teltonika fragment before or after handshake: pass through
                ctx.fireChannelRead(result);
                ctx.fireChannelRead(buf.retain());
                return;
            }

            // === GT06 / TK103 ===
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

            // === Unknown protocol ===
            pipeline.remove(this);
            logger.warn("Unknown protocol '" + protocol + "' — removed detection handler");
            ctx.fireChannelRead(buf);
            return;
        }

        // Detection failed: forward raw
        ctx.fireChannelRead(buf);
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
        resp[2] = 0x05;
        resp[3] = 0x01;
        resp[4] = (byte) (serial >> 8);
        resp[5] = (byte) (serial & 0xFF);
        resp[6] = 0x01;
        int crc = Checksum.crc16(Checksum.CRC16_X25, ByteBuffer.wrap(resp, 2, 5));
        resp[7] = (byte) (crc >> 8);
        resp[8] = (byte) crc;
        resp[9] = 0x0D;
        resp[10] = 0x0A;
        return resp;
    }
}
