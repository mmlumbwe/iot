package com.assettrack.iot.network.handlers;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.netty.channel.socket.SocketChannel;

@Component
@ChannelHandler.Sharable
public class NetworkMessageHandler extends SimpleChannelInboundHandler<DeviceMessage> {

    private static final Logger logger = LoggerFactory.getLogger(NetworkMessageHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DeviceMessage message) {
        if (message == null || message.isDuplicate()) {
            logger.warn("Ignoring null or duplicate message");
            return;
        }

        // Set channel information
        message.setChannel((SocketChannel) ctx.channel());
        message.setRemoteAddress(ctx.channel().remoteAddress());

        if (message.getImei() == null || message.getImei().isEmpty()) {
            logger.error("Message missing IMEI");
            return;
        }

        processMessage(message, ctx);
    }

    private void processMessage(DeviceMessage message, ChannelHandlerContext ctx) {
        try {
            switch (message.getMessageType()) {
                case DeviceMessage.TYPE_LOGIN:
                    handleLogin(message, ctx);
                    break;
                case DeviceMessage.TYPE_LOCATION:
                    handleGps(message, ctx);
                    break;
                case DeviceMessage.TYPE_HEARTBEAT:
                    handleHeartbeat(message, ctx);
                    break;
                case DeviceMessage.TYPE_ALARM:
                    handleAlarm(message, ctx);
                    break;
                case DeviceMessage.TYPE_ERROR:
                    handleError(message);
                    break;
                default:
                    logger.warn("Unknown message type: {}", message.getMessageType());
            }
        } catch (Exception e) {
            logger.error("Error processing message from {}", message.getImei(), e);
        }
    }

    private void handleLogin(DeviceMessage message, ChannelHandlerContext ctx) {
        if (message.getResponseData() != null) {
            ctx.writeAndFlush(message.getResponseData());
            logger.info("Sent login acknowledgment for {}", message.getImei());
        }
    }

    private void handleGps(DeviceMessage message, ChannelHandlerContext ctx) {
        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            logger.info("Received position for {}: {}", message.getImei(), position);
            if (message.getResponseData() != null) {
                ctx.writeAndFlush(message.getResponseData());
            }
        }
    }

    private void handleHeartbeat(DeviceMessage message, ChannelHandlerContext ctx) {
        logger.info("Heartbeat received from {}", message.getImei());
        if (message.getResponseData() != null) {
            ctx.writeAndFlush(message.getResponseData());
        }
    }

    private void handleAlarm(DeviceMessage message, ChannelHandlerContext ctx) {
        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            logger.warn("ALARM for {}: {} at {}",
                    message.getImei(),
                    position.getAlarmType(),
                    position);
            if (message.getResponseData() != null) {
                ctx.writeAndFlush(message.getResponseData());
            }
        }
    }

    private void handleError(DeviceMessage message) {
        logger.error("Error message from {}: {}", message.getImei(), message.getError());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel error: {}", cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("Channel closed: {}", ctx.channel());
        ctx.close();
    }
}