package com.assettrack.iot.protocol;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;

public interface ProtocolHandler {
    boolean supports(String protocolType);
    DeviceMessage handle(byte[] data) throws ProtocolException;
    boolean canHandle(String protocol, String version);
    Position parsePosition(byte[] rawMessage);
    byte[] generateResponse(Position position);
}
