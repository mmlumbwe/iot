package com.assettrack.iot.config;

public class Checksum {
    public static final int CRC16_X25 = 0x1021; // CRC-16/X25 polynomial

    public static int crc16(int polynomial, byte[] data) {
        int crc = 0xFFFF; // Initial value

        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ polynomial;
                } else {
                    crc <<= 1;
                }
            }
        }

        return crc & 0xFFFF;
    }
}