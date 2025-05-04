package com.assettrack.iot.service;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.protocol.ProtocolDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class UdpServer {
    private static final Logger logger = LoggerFactory.getLogger(UdpServer.class);

    @Value("${udp.server.port:5023}")
    private int udpPort;

    @Value("${udp.server.threads:4}")
    private int threadPoolSize;

    private DatagramSocket udpSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;

    @Autowired
    private ProtocolService protocolService;

    @Autowired
    private ProtocolDetector protocolDetector;

    @PostConstruct
    public void start() {
        try {
            udpSocket = new DatagramSocket(udpPort);
            executorService = Executors.newFixedThreadPool(threadPoolSize);
            running = true;

            logger.info("UDP server started on port {}", udpPort);

            // Start listener thread
            new Thread(this::listen).start();
        } catch (SocketException e) {
            logger.error("Failed to start UDP server on port {}", udpPort, e);
        }
    }

    private void listen() {
        byte[] buffer = new byte[2048]; // Adjust based on your protocol's max packet size

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                // Handle packet in thread pool
                executorService.execute(() -> handlePacket(packet));
            } catch (IOException e) {
                if (running) {
                    logger.error("Error receiving UDP packet", e);
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        try {
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            String clientAddress = packet.getAddress().getHostAddress();
            int clientPort = packet.getPort();

            logger.debug("Received UDP packet from {}:{} ({} bytes)",
                    clientAddress, clientPort, data.length);

            ProtocolDetector.ProtocolDetectionResult detection = protocolDetector.detect(data);
            String protocol = detection.getProtocol();

            if (!detection.isValid()) {
                logger.warn("Invalid protocol packet from {}:{}. Error: {}",
                        clientAddress, clientPort, detection.getError());
                return;
            }

            // Handle Teltonika IMEI packet directly
            if ("TELTONIKA".equals(protocol) && "IMEI".equals(detection.getPacketType())) {
                byte[] response = new byte[]{0x01}; // Accept IMEI
                sendResponse(packet.getAddress(), packet.getPort(), response);
                return;
            }

            DeviceMessage message = protocolService.parseData(protocol, data);
            if (message != null && message.getParsedData().containsKey("response")) {
                byte[] response = (byte[]) message.getParsedData().get("response");
                sendResponse(packet.getAddress(), packet.getPort(), response);
            }
        } catch (Exception e) {
            logger.error("Error processing UDP packet", e);
        }
    }

    public void sendResponse(InetAddress address, int port, byte[] response) {
        try {
            DatagramPacket responsePacket = new DatagramPacket(
                    response,
                    response.length,
                    address,
                    port
            );
            udpSocket.send(responsePacket);
            logger.debug("Sent UDP response to {}:{} ({} bytes)",
                    address.getHostAddress(), port, response.length);
        } catch (IOException e) {
            logger.error("Failed to send UDP response", e);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;

        if (udpSocket != null) {
            udpSocket.close();
            logger.info("UDP server stopped");
        }

        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
