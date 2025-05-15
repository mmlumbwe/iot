package com.assettrack.iot.network.handlers;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.ConnectionManager;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class NetworkMessageHandler extends SimpleChannelInboundHandler<Position> {

    private final ConnectionManager connectionManager;

    public NetworkMessageHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Position position) {
        // Process the position update
        System.out.println("Received position: " + position);
        // You would typically:
        // 1. Validate the position
        // 2. Store it in database
        // 3. Update device session
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}