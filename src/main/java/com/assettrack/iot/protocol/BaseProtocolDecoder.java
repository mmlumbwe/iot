package com.assettrack.iot.protocol;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.ByteBuf;
import com.assettrack.iot.session.ConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class BaseProtocolDecoder extends ChannelInboundHandlerAdapter {

    private final ConnectionManager connectionManager;

    @Autowired
    public BaseProtocolDecoder(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                Object decoded = decode(ctx, buf);
                if (decoded != null) {
                    ctx.fireChannelRead(decoded);
                }
            } finally {
                buf.release();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf) {
        // Implement your protocol-specific decoding here
        return null;
    }
}