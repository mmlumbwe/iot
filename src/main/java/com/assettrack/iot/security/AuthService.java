package com.assettrack.iot.security;

public interface AuthService {
    boolean authenticate(String imei, String protocol, byte[] authData);
}
