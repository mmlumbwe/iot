package com.assettrack.iot.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Dynamically detects protocol (Teltonika, GT06, TK103) and inserts appropriate framers.
 * For Teltonika, it specifically handles the IMEI handshake and then adds the AVL data framer.
 */
public class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    private final ProtocolDetector protocolDetector;
    private final TeltonikaHandler teltonikaHandler;
    private final Gt06Handler gt06Handler;

    // State to track if IMEI handshake has occurred for Teltonika
    private boolean teltonikaImeiHandled = false;

    public ProtocolDetectionHandler(
            ProtocolDetector protocolDetector,
            TeltonikaHandler teltonikaHandler,
            Gt06Handler gt06Handler) {
        this.protocolDetector = protocolDetector;
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler = gt06Handler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // We will read a snapshot of the buffer for protocol detection
        // The buffer's reader index is not advanced by getBytes.
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);

        logger.info("Protocol detection for packet: {}", Hex.encodeHexString(data));

        ProtocolDetector.ProtocolDetectionResult protocolResult = protocolDetector.detect(data);

        if (protocolResult.isSuccess()) {
            logger.info("Detected {} protocol: {}, Packet Type: {}",
                    protocolResult.getProtocol(), protocolResult.getVersion(), protocolResult.getPacketType());

            if (protocolResult.getProtocol().equals("TELTONIKA")) {
                if (protocolResult.getPacketType().equals("IMEI")) {
                    if (!teltonikaImeiHandled) {
                        // This is the IMEI packet. ProtocolDetectionHandler will handle it directly.
                        // Ensure we have enough data for a complete IMEI packet (17 bytes)
                        if (buf.readableBytes() < 17) {
                            logger.warn("Incomplete IMEI packet received. Waiting for more data.");
                            // Retain the buffer if it's incomplete to prevent premature release
                            ReferenceCountUtil.retain(buf);
                            return; // Wait for more data in the next read
                        }

                        // Consume the 17 bytes of the IMEI packet
                        byte[] imeiBytes = new byte[17];
                        buf.readBytes(imeiBytes); // This advances the reader index of the ByteBuf

                        try {
                            // Process IMEI and send 0x01 response
                            teltonikaHandler.handle(imeiBytes, ctx);
                            logger.info("Accepted IMEI: {}", new String(imeiBytes, 2, 15, StandardCharsets.US_ASCII));
                            teltonikaImeiHandled = true; // Mark IMEI as handled
                        } catch (Exception e) {
                            logger.error("Error handling Teltonika IMEI packet", e);
                            ReferenceCountUtil.release(buf); // Release the buffer on error
                            ctx.close();
                            return;
                        }

                        // IMEI handshake is complete. Now add the AVL data framer.
                        // Ensure 'teltonikaAvlFrame' is not already in the pipeline before adding
                        if (ctx.pipeline().get("teltonikaAvlFrame") == null) {
                            ctx.pipeline().addBefore("protocolDetector", "teltonikaAvlFrame",
                                    new LengthFieldBasedFrameDecoder(
                                            1024 * 1024, // maxFrameLength: 1MB (Teltonika AVL data can be large)
                                            4,            // lengthFieldOffset: from beginning of AVL data (after preamble)
                                            4,            // lengthFieldLength: 4 bytes for data length
                                            0,            // lengthAdjustment: no adjustment needed for AVL
                                            8,            // initialBytesToStrip: 4 bytes for preamble + 4 bytes for data length
                                            true          // failFast: immediately close if frame is too long
                                    )
                            );
                            logger.info("Added teltonikaAvlFrame for Teltonika AVL data.");
                        }

                        // This handler has completed its dynamic framing role for Teltonika. Remove itself.
                        // Ensure 'protocolDetector' (this handler) is still in the pipeline before attempting to remove
                        if (ctx.pipeline().get("protocolDetector") != null) {
                            ctx.pipeline().remove(this);
                            logger.info("ProtocolDetectionHandler removed for Teltonika protocol.");
                        }

                        // If there's remaining data in the buffer after processing IMEI,
                        // re-fire channelRead so the newly added teltonikaAvlFrame can process it.
                        // This is crucial if AVL data immediately follows the IMEI in the same ByteBuf.
                        if (buf.readableBytes() > 0) {
                            ctx.fireChannelRead(buf);
                        } else {
                            ReferenceCountUtil.release(buf); // Release the buffer if it's empty after IMEI processing
                        }
                        return; // Done processing this buffer in this handler
                    } else {
                        // If teltonikaImeiHandled is true, and this is another IMEI packet, it's unexpected.
                        // Or if the handler somehow wasn't removed and it's an AVL data packet.
                        // The primary goal is that this handler should be removed after the initial setup.
                        logger.warn("Unexpected Teltonika IMEI packet after handshake or handler not removed. Passing to next handler.");
                        ctx.fireChannelRead(buf); // Pass to next handler, expecting teltonikaAvlFrame to process it
                        return;
                    }
                } else { // Teltonika but not IMEI (i.e., AVL data)
                    if (teltonikaImeiHandled) {
                        // This indicates an AVL data packet after IMEI handshake.
                        // If this handler is still here, it implies a logic error where it wasn't removed
                        // or previous buffer had more data. The teltonikaAvlFrame should already be in pipeline and handle it.
                        logger.warn("ProtocolDetectionHandler still active after Teltonika IMEI handshake and receiving AVL data. This should not happen. Passing to next handler.");
                        ctx.fireChannelRead(buf); // Let the teltonikaAvlFrame handle it
                        return;
                    } else {
                        // Teltonika protocol detected, but it's not IMEI and IMEI handshake hasn't happened.
                        // This is an invalid state. Close the connection.
                        logger.error("Teltonika protocol detected but not IMEI packet before IMEI handshake. Closing channel.");
                        ReferenceCountUtil.release(buf);
                        ctx.close();
                        return;
                    }
                }
            } else {
                // Handle GT06/TK103 or other protocols by setting up their framing and removing self
                setupFramingAndRemoveSelf(ctx.pipeline(), protocolResult.getProtocol());
                // After framer is set and this handler removed, re-read so the new framer gets the data.
                ReferenceCountUtil.release(buf); // Release the initial buffer as it's been processed and now the new framer will handle it.
                ctx.read(); // Trigger a read operation for the newly added framer to process the current (or next) inbound data
                return;
            }
        } else {
            logger.error("No protocol detected for data: {}", Hex.encodeHexString(data));
            ReferenceCountUtil.release(buf); // Release the buffer
            ctx.close(); // Close connection for unknown protocols
            return;
        }
    }

    /**
     * Helper method to setup framing and remove this handler for other protocols (GT06, TK103).
     * This method ensures the ProtocolDetectionHandler is removed after it has set up the specific framer.
     *
     * @param pipeline The channel pipeline.
     * @param protocol The detected protocol type.
     */
    private void setupFramingAndRemoveSelf(ChannelPipeline pipeline, String protocol) {
        switch (protocol) {
            case "GT06":
            case "TK103":
                // Check if the specific framer for GT06/TK103 is already in the pipeline before adding
                if (pipeline.get("gt06Tk103Frame") == null) {
                    pipeline.addBefore("protocolDetector", "gt06Tk103Frame",
                            new DelimiterBasedFrameDecoder(
                                    1024, true, // 1024 bytes max frame length, strip delimiters
                                    Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A}) // CRLF delimiter
                            )
                    );
                    logger.info("Added gt06Tk103Frame for {} protocol.", protocol);
                }
                break;
            default:
                logger.warn("No specific framing configured for non-Teltonika protocol: {}. Removing ProtocolDetectionHandler.", protocol);
        }
        // Always remove this handler once framer is set for GT06/TK103 or if no specific framing is needed,
        // to prevent it from interfering with subsequent processing.
        if (pipeline.get("protocolDetector") != null) {
            pipeline.remove(this);
            logger.info("ProtocolDetectionHandler removed for {} protocol.", protocol);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ProtocolDetectionHandler: Channel error", cause);
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
}