// NetworkMessageHandler.java
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
        if (message == null || message.isDuplicate()) {
            logger.warn("Ignoring null or duplicate message");
            return;
        }

        message.setChannel((SocketChannel) ctx.channel());
        message.setRemoteAddress(ctx.channel().remoteAddress());

        DeviceSession session = sessionManager.getSessionByChannel(ctx.channel());

        // Basic logging for all incoming messages
        logger.info("Received message type: {} from Device ID: {}", message.getMessageType(), message.getDeviceId());

        switch (message.getMessageType()) {
            case "IMEI":
                handleImeiMessage(message, session);
                break;
            case "DATA":
                handleDataMessage(message, session);
                break;
            case "HEARTBEAT":
                handleHeartbeat(message, session);
                break;
            case "ALARM":
                handleAlarm(message, session);
                break;
            case "ERROR":
                handleError(message, session);
                break;
            default:
                logger.warn("Unknown message type: {}", message.getMessageType());
                break;
        }

        // If there's a response to send back to the device, send it
        if (message.getResponseData() != null) {
            ctx.writeAndFlush(message.getResponseData());
            logger.debug("Sent response for message type {} to device {}", message.getMessageType(), message.getDeviceId());
        }
    }

    private void handleImeiMessage(DeviceMessage message, DeviceSession session) {
        // For IMEI messages, the session would have already been created/updated
        // upstream by TeltonikaHandler calling SessionManager.getOrCreateSession.
        // Here, we can just log or perform any post-session-creation logic.
        if (session != null) {
            logger.info("IMEI message received from device {}. Session established/updated.", session.getImei());
            // You might want to respond with a login acknowledgment if not already done by TeltonikaHandler
            // This example assumes TeltonikaHandler already sends the 0x01 acknowledgement.
        } else {
            logger.warn("IMEI message received but no session found for channel {}. This should not happen if TeltonikaHandler is working correctly.", message.getChannel().id());
        }
    }

    private void handleDataMessage(DeviceMessage message, DeviceSession session) {
        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            logger.info("Received GPS data for device {}: Latitude={}, Longitude={}, Time={}",
                    message.getDeviceId(), position.getLatitude(), position.getLongitude(), position.getFixTime());
            // Here you would typically save the position data to a database,
            // push to a real-time system, or perform other business logic.
            cacheManager.updateDevicePosition(message.getDeviceId(), position); // Assuming cacheManager handles position updates
        } else {
            logger.warn("Received DATA message for device {} but no position data found.", message.getDeviceId());
        }
    }

    private void handleHeartbeat(DeviceMessage message, DeviceSession session) {
        logger.info("Heartbeat received from {}", session.getDeviceId());
        // No specific action needed beyond logging and potential response (if `responseData` is set)
    }

    private void handleAlarm(DeviceMessage message, DeviceSession session) {
        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            logger.warn("ALARM for {}: {} at {}",
                    session.getDeviceId(),
                    position.getAlarmType(),
                    position);
            // Implement alarm specific logic (e.g., send notifications)
        } else {
            logger.warn("Received ALARM message for device {} but no position data found.", message.getDeviceId());
        }
    }

    private void handleError(DeviceMessage message, DeviceSession session) {
        logger.error("Error message from {}: {}", session.getDeviceId(), message.getError());
        // Implement error handling logic (e.g., log to an error monitoring system)
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel error for {}: {}", ctx.channel().id(), cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            sessionManager.removeSession(ctx.channel());
            logger.info("Channel {} closed, session removed", ctx.channel().id());
        } finally {
            ctx.close();
        }
    }
}