package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.session.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.socket.SocketChannel;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public abstract class BaseProtocolDecoder extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BaseProtocolDecoder.class);

    protected final ProtocolDetector protocolDetector;
    protected final SessionManager sessionManager;

    @Autowired
    public BaseProtocolDecoder(SessionManager sessionManager, ProtocolDetector protocolDetector) {
        this.sessionManager = sessionManager;
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
            buf.getBytes(buf.readerIndex(), data);

            ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);

            if (result != null) {
                logDetectionResult(result, data);
            } else {
                logger.warn("Protocol detection returned null for data: {}", bytesToHex(data));
                ReferenceCountUtil.release(buf);
                return;
            }

            if (result.isValid()) {
                ctx.channel().attr(ProtocolConstants.PROTOCOL_ATTRIBUTE).set(result.getProtocol());
                Object decoded = decode(ctx, buf, result);
                if (decoded != null) {
                    ctx.fireChannelRead(decoded);
                }
            } else {
                logger.warn("Invalid protocol detection: {}", result.getError());
                ReferenceCountUtil.release(buf);
            }
        } catch (Exception e) {
            logger.error("Error during protocol detection", e);
            ReferenceCountUtil.release(buf);
        }
    }

    /**
     * Abstract method to be implemented by protocol-specific decoders
     * @param data The raw byte data to handle
     * @return Processed DeviceMessage
     * @throws ProtocolException if protocol parsing fails
     */
    protected abstract DeviceMessage handle(byte[] data) throws ProtocolException;

    //@Override
    protected Object decode(ChannelHandlerContext ctx,
                            ByteBuf buf,
                            ProtocolDetector.ProtocolDetectionResult result) {
        try {
            if (result == null || !"GT06".equals(result.getProtocol())) {
                return null;
            }

            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);

            // Fallback detection for GT06
            if ((result == null || !"GT06".equals(result.getProtocol()))) {
                if (data.length >= 2 && data[0] == 0x78 && data[1] == 0x78) {
                    result = ProtocolDetector.ProtocolDetectionResult.success("GT06", "LOGIN", "1.0");
                    logger.info("Manually detected GT06 packet");
                } else {
                    return null;
                }
            }


            DeviceMessage message = handle(data);

            if (message != null) {
                message.setProtocolType("GT06");
                if (ctx.channel() instanceof SocketChannel) {
                    message.setChannel((SocketChannel) ctx.channel());
                }
                message.setRemoteAddress(ctx.channel().remoteAddress());

                if (message.getImei() != null) {
                    message.addParsedData("deviceId", generateDeviceId(message.getImei()));
                }
            }
            return message;
        } catch (Exception e) {
            logger.error("Decoding error", e);
            return null;
        } finally {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    protected long generateDeviceId(String imei) {
        return imei != null ? imei.hashCode() & 0xffffffffL : 0L;
    }

    private void logDetectionResult(ProtocolDetector.ProtocolDetectionResult result, byte[] data) {
        if (logger.isDebugEnabled()) {
            logger.debug("Detected protocol: {}, Type: {}, Version: {}",
                    result.getProtocol(), result.getPacketType(), result.getVersion());
            logger.debug("Raw data ({} bytes): {}", data.length, bytesToHex(data));
        }
        if (result == null) {
            logger.warn("Protocol detection failed for data: {}", bytesToHex(data));
        } else {
            logger.info("Detected protocol: {}", result.getProtocol());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}