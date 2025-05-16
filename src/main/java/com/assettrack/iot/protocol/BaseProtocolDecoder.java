package com.assettrack.iot.protocol;

import com.assettrack.iot.session.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.ByteBuf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class BaseProtocolDecoder extends ChannelInboundHandlerAdapter {

    private final SessionManager sessionManager;

    @Autowired
    public BaseProtocolDecoder(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
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