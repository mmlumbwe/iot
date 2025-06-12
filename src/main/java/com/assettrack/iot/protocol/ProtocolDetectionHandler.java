package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ProtocolDetectionHandler extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 2) {
            return; // wait for enough data to detect
        }

        int readerIndex = in.readerIndex();
        byte b1 = in.getByte(readerIndex);
        byte b2 = in.getByte(readerIndex + 1);

        String protocol;
        LengthFieldBasedFrameDecoder frameDecoder;

        // GT06: header 0x7878 or 0x7979, length field at offset 2 (2 bytes)
        if ((b1 == 0x78 && b2 == 0x78) || (b1 == 0x79 && b2 == 0x79)) {
            protocol = "GT06";
            frameDecoder = new LengthFieldBasedFrameDecoder(
                    2048,   // max frame length
                    2,      // length field offset
                    2,      // length field length
                    0,      // length adjustment
                    4       // initial bytes to strip (header + length)
            );

            // TK103: first byte 0x80, length field at offset 2 (1 byte)
        } else if (b1 == (byte) 0x80) {
            protocol = "TK103";
            frameDecoder = new LengthFieldBasedFrameDecoder(
                    1024,   // max frame length
                    2,      // length field offset
                    1,      // length field length
                    0,      // length adjustment
                    3       // strip header + length bytes
            );

            // Teltonika: first byte 0x00, 4-byte length field
        } else if (b1 == 0x00) {
            protocol = "TELTONIKA";
            frameDecoder = new LengthFieldBasedFrameDecoder(
                    65536,  // max frame length (enough for large AVL buffers)
                    0,      // length field offset
                    4,      // length field length
                    0,      // length adjustment
                    4       // strip the length field itself
            );

        } else {
            // Unknown protocol: pass through or drop
            LOGGER.warn("Unknown protocol, skipping detection: {} {}", b1, b2);
            ctx.fireChannelRead(in.readBytes(in.readableBytes()));
            return;
        }

        LOGGER.info("Detected {} protocol, installing frame decoder", protocol);
        // Replace this detector with the appropriate frame decoder
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.replace(this, "frameDecoder", frameDecoder);
        // Fire through pipeline so the new decoder can take effect
        ctx.fireChannelRead(in.readBytes(in.readableBytes()));
    }
}
