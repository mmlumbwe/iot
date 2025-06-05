package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.session.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GenericProtocolDecoder extends BaseProtocolDecoder {

    @Autowired
    public GenericProtocolDecoder(SessionManager sessionManager,
                                  ProtocolDetector protocolDetector,
                                  @Autowired(required = false) TeltonikaHandler teltonikaHandler) {
        super(sessionManager, protocolDetector, teltonikaHandler);
    }

    @Override
    protected DeviceMessage handle(byte[] data) {
        // Default implementation for non-Teltonika protocols
        // This will primarily handle GT06 packets
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");
        // Add your GT06 specific parsing logic here
        return message;
    }
}
