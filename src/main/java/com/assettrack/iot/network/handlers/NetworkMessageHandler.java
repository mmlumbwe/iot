package com.assettrack.iot.network.handlers;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.DeviceSession;
import com.assettrack.iot.session.SessionManager;
import com.assettrack.iot.session.cache.CacheManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.netty.channel.socket.SocketChannel;

@Component
@ChannelHandler.Sharable
public class NetworkMessageHandler extends SimpleChannelInboundHandler<DeviceMessage> {

    private static final Logger logger = LoggerFactory.getLogger(NetworkMessageHandler.class);

    private final SessionManager sessionManager;
    private final CacheManager cacheManager;

    @Autowired
    public NetworkMessageHandler(SessionManager sessionManager, CacheManager cacheManager) {
        this.sessionManager = sessionManager;
        this.cacheManager = cacheManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DeviceMessage message) {
        logger.info("Processing message: {}", message);
        if (message == null) {
            logger.warn("Received null message");
            return;
        }

        // Set the channel/remote address if not already set
        if (message.getSocketChannel() == null) {
            message.setChannel((SocketChannel) ctx.channel());
        }
        if (message.getRemoteAddress() == null) {
            message.setRemoteAddress(ctx.channel().remoteAddress());
        }

        String imei = message.getImei();
        if (imei == null || imei.isEmpty()) {
            logger.error("Message missing IMEI");
            return;
        }

        Long deviceId = message.getDeviceId();
        DeviceSession session = sessionManager.getSessionByImei(imei);

        if (session == null && DeviceMessage.TYPE_LOGIN.equals(message.getMessageType())) {
            session = createNewSession(message, ctx, deviceId, imei);
        }

        if (session != null) {
            session.updateLastActivity();
            processMessage(message, session);
        } else {
            logger.warn("No session found for IMEI: {}", imei);
        }
    }

    private DeviceSession createNewSession(DeviceMessage message, ChannelHandlerContext ctx, Long deviceId, String imei) {
        DeviceSession session = new DeviceSession(
                deviceId != null ? deviceId : generateDeviceId(imei),
                imei,
                message.getProtocolType(),
                ctx.channel(),
                ctx.channel().remoteAddress()
        );
        sessionManager.addSession(session);
        logger.info("Created new session for IMEI: {}", imei);
        return session;
    }

    private long generateDeviceId(String imei) {
        return imei.hashCode() & 0xffffffffL;
    }

    private void processMessage(DeviceMessage message, DeviceSession session) {
        try {
            switch (message.getMessageType()) {
                case DeviceMessage.TYPE_LOGIN:
                    handleLogin(message, session);
                    break;
                case DeviceMessage.TYPE_LOCATION:
                    handleGps(message, session);
                    break;
                case DeviceMessage.TYPE_HEARTBEAT:
                    handleHeartbeat(message, session);
                    break;
                case DeviceMessage.TYPE_ALARM:
                    handleAlarm(message, session);
                    break;
                case DeviceMessage.TYPE_ERROR:
                    handleError(message, session);
                    break;
                default:
                    logger.warn("Unknown message type: {}", message.getMessageType());
            }

            updateDeviceStatus(message, session);
        } catch (Exception e) {
            logger.error("Error processing message from {}", session.getDeviceId(), e);
        }
    }

    private void updateDeviceStatus(DeviceMessage message, DeviceSession session) {
        if (message.getBatteryLevel() > 0) {
            //cacheManager.updateDeviceBattery(session.getDeviceId(), message.getBatteryLevel());
        }
        if (message.getSignalStrength() > 0) {
            //cacheManager.updateDeviceSignal(session.getDeviceId(), message.getSignalStrength());
        }
    }

    private void handleLogin(DeviceMessage message, DeviceSession session) {
        if (message.getResponseData() != null) {
            session.getChannel().writeAndFlush(message.getResponseData());
            logger.debug("Sent login acknowledgment for {}", session.getDeviceId());
        }
    }

    private void handleGps(DeviceMessage message, DeviceSession session) {
        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            logger.info("Received position for {}: {}", session.getDeviceId(), position);
            if (message.getResponseData() != null) {
                session.getChannel().writeAndFlush(message.getResponseData());
            }
        }
    }

    private void handleHeartbeat(DeviceMessage message, DeviceSession session) {
        logger.debug("Heartbeat received from {}", session.getDeviceId());
        if (message.getResponseData() != null) {
            session.getChannel().writeAndFlush(message.getResponseData());
        }
    }

    private void handleAlarm(DeviceMessage message, DeviceSession session) {
        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            logger.warn("ALARM for {}: {} at {}",
                    session.getDeviceId(),
                    position.getAlarmType(),
                    position);
            if (message.getResponseData() != null) {
                session.getChannel().writeAndFlush(message.getResponseData());
            }
        }
    }

    private void handleError(DeviceMessage message, DeviceSession session) {
        logger.error("Error message from {}: {}", session.getDeviceId(), message.getError());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel error: {}", cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            sessionManager.removeSession(ctx.channel());
            logger.info("Channel closed, session removed");
        } finally {
            ctx.close();
        }
    }
}