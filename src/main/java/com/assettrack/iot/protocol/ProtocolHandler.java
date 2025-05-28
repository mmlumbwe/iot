package com.assettrack.iot.protocol;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import io.netty.channel.ChannelHandlerContext;
import org.apache.coyote.ProtocolException;

public interface ProtocolHandler {
    boolean supports(String protocolType);
    DeviceMessage handle(byte[] data) throws ProtocolException;
    DeviceMessage handle(byte[] data, ChannelHandlerContext ctx) throws ProtocolException;
    boolean canHandle(String protocol, String version);
    Position parsePosition(byte[] rawMessage);
    byte[] generateResponse(Position position);
}
