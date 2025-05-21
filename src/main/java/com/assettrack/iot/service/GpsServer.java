package com.assettrack.iot.service;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.DeviceSession;
import com.assettrack.iot.protocol.Gt06Handler;
import com.assettrack.iot.protocol.ProtocolDetector;
import com.assettrack.iot.protocol.ProtocolHandler;
import com.assettrack.iot.protocol.TeltonikaHandler;
import com.assettrack.iot.session.SessionManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.codec.binary.Hex;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.assettrack.iot.network.TrackerPipelineFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


import static com.assettrack.iot.protocol.Gt06Handler.*;

@Component
public class GpsServer {
    private static final Logger logger = LoggerFactory.getLogger(GpsServer.class);
    private static final Logger trafficLogger = LoggerFactory.getLogger("TRAFFIC");

    // Network configuration
    private static final int SOCKET_TIMEOUT = 30000; // 30 seconds
    private static final int MAX_PACKET_SIZE = 2048;
    private static final int MAX_CONNECTION_AGE = 300000; // 5 minutes
    private static final int MAX_RECONNECTIONS_BEFORE_ALERT = 5;
    private static final int SUSPICIOUS_CONNECTION_THRESHOLD = 10;
    private static final long SUSPICIOUS_TIME_WINDOW = 60000; // 1 minute
    private static final long CONNECTION_TIMEOUT = 300000; // 5 minutes

    // Protocol constants
    private static final byte PROTOCOL_HEADER_1 = 0x78;
    private static final byte PROTOCOL_HEADER_2 = 0x78;
    private static final byte PROTOCOL_LOGIN = 0x01;
    private static final byte PROTOCOL_GPS = 0x12;
    private static final byte PROTOCOL_HEARTBEAT = 0x13;
    private static final byte PROTOCOL_ALARM = 0x16;
    private static final byte PROTOCOL_ERROR = 0x7F;

    private static final String PROTOCOL_TELTONIKA = "TELTONIKA";
    private static final String PROTOCOL_GT06 = "GT06";
    private static final String PACKET_TYPE_IMEI = "IMEI";
    private static final String PACKET_TYPE_LOGIN = "LOGIN";
    private static final String PACKET_TYPE_DATA = "DATA";
    private static final String PACKET_TYPE_HEARTBEAT = "HEARTBEAT";
    private static final String PACKET_TYPE_ALARM = "ALARM";

    // Configuration properties
    @Value("${gps.server.tcp.port:5023}")
    private int tcpPort;

    @Value("${gps.server.udp.port:5023}")
    private int udpPort;

    @Value("${gps.server.threads:10}")
    private int maxThreads;

    @Value("${gps.server.max.connections:100}")
    private int maxConnections;

    @Value("${gps.server.worker.threads:0}") // 0 = auto-detect
    private int workerThreads;

    @Value("${gps.server.so.backlog:128}")
    private int soBacklog;

    @Value("${gps.server.shutdown.timeout:5000}")
    private long shutdownTimeout;

    // Dependencies
    private final PositionService positionService;
    private final ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket udpSocket;
    private ServerSocket tcpServerSocket;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @Autowired private ProtocolDetector protocolDetector;
    @Autowired private Gt06Handler gt06Handler;
    @Autowired private TeltonikaHandler teltonikaHandler;
    @Autowired private List<ProtocolHandler> protocolHandlers;
    @Autowired private SessionManager sessionManager;
    @Autowired private TrackerPipelineFactory pipelineFactory;

    // Connection tracking
    private final Map<SocketAddress, DeviceSession> addressToSessionMap = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> activeConnections = new ConcurrentHashMap<>();
    private final Set<String> blacklistedIps = ConcurrentHashMap.newKeySet();
    private final Map<String, ConnectionInfo> connectionInfoMap = new ConcurrentHashMap<>();

    public GpsServer(PositionService positionService,
                     @Value("${gps.server.threads:10}") int maxThreads) {
        this.positionService = positionService;
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("Thread pool size must be positive");
        }
        this.maxThreads = maxThreads;
        this.threadPool = Executors.newFixedThreadPool(maxThreads);
        logger.info("Initialized GPS Server with thread pool size: {}", maxThreads);
    }

    @PostConstruct
    public void start() {
        running.set(true);
        logger.info("Initializing GPS Server...");
        logger.info("Configuration - TCP Port: {}, UDP Port: {}, Max Threads: {}, Max Connections: {}",
                tcpPort, udpPort, maxThreads, maxConnections);

        startNettyTcpServer();
        //startLegacyTcpServer();
        //startUdpServer();

        logger.info("GPS Server successfully started (TCP:{}, UDP:{})", tcpPort, udpPort);
        trafficLogger.info("SERVER_STARTED ports={},{}", tcpPort, udpPort);
    }

    @PreDestroy
    public void stop() {
        logger.info("Shutting down GPS Server...");
        trafficLogger.info("SERVER_SHUTDOWN_INITIATED");
        running.set(false);
        gracefulShutdown();
        logger.info("GPS Server shutdown complete");
        trafficLogger.info("SERVER_SHUTDOWN_COMPLETED");
    }

    private void startNettyTcpServer() {
        logger.info("Starting Netty TCP server on port {}", tcpPort);
        int actualWorkerThreads = workerThreads > 0 ? workerThreads : Runtime.getRuntime().availableProcessors() * 2;
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(actualWorkerThreads);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(pipelineFactory)
                    .option(ChannelOption.SO_BACKLOG, soBacklog)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            ChannelFuture f = b.bind(tcpPort).sync();
            serverChannel = f.channel();
            logger.info("Netty TCP server started successfully on port {}", tcpPort);
        } catch (InterruptedException e) {
            logger.error("Netty server startup interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Failed to start Netty TCP server", e);
        }
    }

    @Async
    protected void startLegacyTcpServer() {
        logger.info("Starting legacy TCP server on port {}", tcpPort);
        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            serverSocket.setReuseAddress(true);
            serverSocket.setSoTimeout(SOCKET_TIMEOUT);

            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    configureClientSocket(clientSocket);

                    if (shouldAcceptConnection(clientSocket)) {
                        threadPool.execute(() -> handleTcpClient(clientSocket));
                    } else {
                        clientSocket.close();
                    }
                } catch (SocketTimeoutException e) {
                    // Normal during operation
                } catch (IOException e) {
                    logger.error("Accept error", e);
                }
            }
        } catch (IOException e) {
            logger.error("Legacy TCP server failed", e);
        }
    }

    @Async
    protected void startUdpServer() {
        logger.info("Starting UDP server on port {}", udpPort);
        try {
            udpSocket = new DatagramSocket(udpPort);
            udpSocket.setSoTimeout(SOCKET_TIMEOUT);
            logger.info("UDP server successfully bound to port {}", udpPort);

            byte[] buffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (running.get()) {
                try {
                    logger.trace("Waiting for UDP datagram...");
                    udpSocket.receive(packet);

                    String clientIp = packet.getAddress().getHostAddress();
                    if (blacklistedIps.contains(clientIp)) {
                        logger.debug("Ignoring UDP packet from blacklisted IP: {}", clientIp);
                        continue;
                    }

                    trafficLogger.debug("UDP_RECEIVED from={}:{}, bytes={}",
                            clientIp, packet.getPort(), packet.getLength());

                    threadPool.execute(() -> handleUdpPacket(packet));
                    packet.setLength(buffer.length);
                } catch (SocketTimeoutException e) {
                    logger.trace("UDP receive timeout (normal operation)");
                } catch (IOException e) {
                    if (!running.get()) break; // Shutdown requested
                    logger.error("UDP Server error", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start UDP server", e);
        } finally {
            if (udpSocket != null) {
                udpSocket.close();
            }
            logger.info("UDP server stopped");
        }
    }

    private void gracefulShutdown() {
        logger.debug("Initiating graceful shutdown");

        // Shutdown Netty server
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Shutdown thread pools
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Shutdown Netty event loops
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }

        // Close sockets
        if (udpSocket != null) {
            udpSocket.close();
        }
        if (tcpServerSocket != null) {
            try {
                tcpServerSocket.close();
            } catch (IOException e) {
                logger.warn("Error closing TCP socket", e);
            }
        }
    }



    //---------------------------------

    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupBlacklist() {
        logger.info("Current blacklist size: {}", blacklistedIps.size());
        // Implement logic to expire old blacklist entries if needed
    }

    @Async
    protected void startTcpServer() {
        logger.info("Starting TCP server on port {}", tcpPort);
        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            serverSocket.setReuseAddress(true);
            serverSocket.setSoTimeout(SOCKET_TIMEOUT);

            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    configureClientSocket(clientSocket);

                    if (shouldAcceptConnection(clientSocket)) {
                        threadPool.execute(() -> handleTcpClient(clientSocket));
                    } else {
                        clientSocket.close();
                    }
                } catch (SocketTimeoutException e) {
                    // Normal during operation
                } catch (IOException e) {
                    logger.error("Accept error", e);
                }
            }
        } catch (IOException e) {
            logger.error("Server failed", e);
        }
    }

    private void configureClientSocket(Socket socket) throws SocketException {
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(SOCKET_TIMEOUT);

        // For Java 11+
        try {
            socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        } catch (UnsupportedOperationException ignored) {} catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean shouldAcceptConnection(Socket socket) {
        // Your connection validation logic
        return true;
    }

    // Add this scheduled task to clean up stale connections
    @Scheduled(fixedRate = 60000) // Run every minute

    private void handleTcpClient(Socket clientSocket) {
        String clientAddress = clientSocket.getInetAddress().getHostAddress();
        int clientPort = clientSocket.getPort();

        if (shouldThrottleConnection(clientAddress)) {
            try {
                clientSocket.close();
                logger.debug("Throttled and closed connection from {}", clientAddress);
                return;
            } catch (IOException e) {
                logger.warn("Error closing throttled connection from {}: {}", clientAddress, e.getMessage());
                return;
            }
        }

        // Track connection info
        ConnectionInfo connectionInfo = connectionInfoMap.compute(clientAddress, (k, v) ->
                v == null ? new ConnectionInfo(clientSocket.getRemoteSocketAddress()) :
                        new ConnectionInfo(v.remoteAddress) {{
                            connectionCount = v.connectionCount + 1;
                        }});

        if (connectionInfo.connectionCount > 10) {
            logger.warn("Multiple reconnections from {}: {}", clientAddress, connectionInfo.connectionCount);
        }

        try (InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {

            byte[] buffer = new byte[MAX_PACKET_SIZE];
            while (!clientSocket.isClosed() && running.get()) {
                int bytesRead = input.read(buffer);
                if (bytesRead == -1) break;

                byte[] receivedData = Arrays.copyOf(buffer, bytesRead);
                DeviceMessage message = processProtocolMessage(receivedData);

                if (message != null) {
                    // Handle message based on type
                    switch (message.getMessageType()) {
                        case "LOGIN":
                            handleLoginMessage(message, clientSocket, output);
                            break;
                        case "GPS":
                        case "LOCATION":
                            handleGpsMessage(message, clientSocket, output);
                            break;
                        case "HEARTBEAT":
                            handleHeartbeatMessage(message, clientSocket, output);
                            break;
                        case "ALARM":
                            handleAlarmMessage(message, clientSocket, output);
                            break;
                        default:
                            logger.warn("Unknown message type: {}", message.getMessageType());
                            break;
                    }

                    // Track connection if we have an IMEI
                    if (message.getImei() != null) {
                        trackConnection(message, clientAddress);
                    }
                }
            }
        } catch (SocketTimeoutException e) {
            logger.debug("Connection timeout for {}:{}", clientAddress, clientPort);
        } catch (Exception e) {
            logger.error("Connection error for {}:{} - {}", clientAddress, clientPort, e.getMessage());
        } finally {
            cleanupConnection(clientSocket);
        }
    }

    private void handleLoginMessage(DeviceMessage message, Socket socket, OutputStream output) throws IOException {
        // Validate input parameters
        Objects.requireNonNull(message, "DeviceMessage cannot be null");
        Objects.requireNonNull(socket, "Socket cannot be null");
        Objects.requireNonNull(output, "OutputStream cannot be null");

        String imei = message.getImei();
        if (imei == null || imei.isEmpty()) {
            logger.error("Invalid login attempt - missing IMEI");
            throw new IOException("Missing IMEI in login message");
        }

        String protocol = message.getProtocol();
        SocketAddress remoteAddress = socket.getRemoteSocketAddress();
        DeviceSession session = null;

        try {
            // Get or create session - using overloaded method without channel
            session = sessionManager.getOrCreateSession(imei, protocol, remoteAddress);

            // Protocol-specific handling
            if (PROTOCOL_GT06.equals(protocol)) {
                short serialNumber = extractSerialNumber(message);

                // Check for duplicate login with serial number validation
                if (session.isDuplicateSerialNumber(serialNumber)) {
                    logger.warn("Duplicate GT06 login from IMEI {} (Serial: {})", imei, serialNumber);

                    // Still send response to keep connection alive
                    byte[] response = generateStandardGt06Response(PROTOCOL_LOGIN, serialNumber, (byte)0x01);
                    output.write(response);
                    output.flush();
                    return;
                }

                // Update session with new serial number
                session.updateSerialNumber(serialNumber);
                session.updateLastActivity();

                // Generate response (use message response if available, otherwise generate default)
                byte[] response = message.getResponseData();
                if (response == null) {
                    response = generateStandardGt06Response(PROTOCOL_LOGIN, serialNumber, (byte)0x01);
                }

                // Send response
                output.write(response);
                output.flush();
                logger.debug("Sent login response to IMEI {}", imei);

                // Update connection state
                session.setConnected(true);
                session.setLastActivity(System.currentTimeMillis());
            } else {
                // Handle other protocols
                logger.debug("Processing login for protocol {}", protocol);
                if (message.getResponseData() != null) {
                    output.write(message.getResponseData());
                    output.flush();
                }
                session.setConnected(true);
                session.setLastActivity(System.currentTimeMillis());
            }
        } catch (IOException e) {
            logger.error("IO error during login for IMEI {}: {}", imei, e.getMessage());
            if (session != null) {
                session.setConnected(false);
            }
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error during login for IMEI {}: {}", imei, e.getMessage());
            if (session != null) {
                session.setConnected(false);
            }
            throw new IOException("Login processing failed", e);
        }
    }

    // Helper method to extract serial number from GT06 message
    private short extractSerialNumber(DeviceMessage message) {
        Object serialObj = message.getParsedData().get("serialNumber");
        if (serialObj instanceof Short) {
            return (Short) serialObj;
        } else if (serialObj instanceof Integer) {
            return ((Integer) serialObj).shortValue();
        }
        throw new IllegalArgumentException("Invalid serial number in message");
    }

    private void handleGpsMessage(DeviceMessage message, Socket socket, OutputStream output) throws IOException {
        String imei = message.getImei();
        if (imei == null) {
            logger.error("Received GPS message without IMEI");
            return;
        }

        // Check session validity for all protocols
        Optional<DeviceSession> sessionOpt = sessionManager.getSession(imei);
        if (!sessionOpt.isPresent()) {
            logger.warn("No active session for IMEI {}, ignoring GPS message", imei);
            return;
        }

        DeviceSession session = sessionOpt.get();

        // Protocol-specific sequence validation
        if (PROTOCOL_GT06.equals(message.getProtocol())) {
            short sequenceNumber = extractSequenceNumber(message);
            if (!session.updateSequenceNumber(sequenceNumber)) {
                logger.debug("Duplicate GPS packet from IMEI {} - ignoring", imei);
                return;
            }
        }

        // Send acknowledgment if response exists
        byte[] response = (byte[]) message.getParsedData().get("response");
        if (response != null) {
            output.write(response);
            output.flush();
        }

        // Process position data
        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            // Update session activity timestamp
            session.updateLastActivity();

            // Ensure position has device reference
            if (position.getDevice() == null) {
                Device device = new Device();
                device.setImei(imei);
                device.setProtocolType(message.getProtocol());
                position.setDevice(device);
            }

            positionService.processAndSavePosition(position);
            logger.info("Processed GPS data for device {}", imei);
        }
    }


    private short extractSequenceNumber(DeviceMessage message) {
        Object seqObj = message.getParsedData().get("sequenceNumber");
        return seqObj instanceof Short ? (short)seqObj : 0;
    }

    private void handleHeartbeatMessage(DeviceMessage message, Socket socket, OutputStream output)
            throws IOException {
        byte[] response = (byte[]) message.getParsedData().get("response");
        if (response != null) {
            output.write(response);
            output.flush();
            if (logger.isTraceEnabled()) {
                logger.trace("Sent heartbeat response to {}", message.getImei());
            }
        }
    }

    private void handleAlarmMessage(DeviceMessage message, Socket socket, OutputStream output)
            throws IOException {
        byte[] response = (byte[]) message.getParsedData().get("response");
        if (response != null) {
            output.write(response);
            output.flush();
        }

        Position position = (Position) message.getParsedData().get("position");
        if (position != null) {
            positionService.processAndSavePosition(position);
            logger.warn("Processed ALARM for device {}: {}",
                    message.getImei(), position.getAlarmType());
        }
    }

    private void cleanupConnection(Socket clientSocket) {
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                connectionInfoMap.remove(clientAddress);
                clientSocket.close();
                logger.info("Closed connection for {}", clientSocket.getRemoteSocketAddress());
            } catch (IOException e) {
                logger.warn("Error closing socket", e);
            }
        }
    }

    private void cleanupConnection(Socket clientSocket, DeviceSession session) {
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                connectionInfoMap.remove(clientAddress);

                clientSocket.close();
                if (session != null) {
                    sessionManager.closeSession(session.getSessionId());
                }
                logger.info("Closed connection for {}", clientSocket.getRemoteSocketAddress());
            } catch (IOException e) {
                logger.warn("Error closing socket", e);
            }
        }
    }

    private void handleTeltonikaProtocol(Socket socket, DeviceMessage message,
                                         DeviceSession session, OutputStream output)
            throws IOException {
        if (PACKET_TYPE_IMEI.equals(message.getMessageType())) {
            processResponse(output, message,
                    socket.getInetAddress().getHostAddress(),
                    socket.getPort());
            handleTeltonikaDataPhase(socket, session);
        } else if (PACKET_TYPE_DATA.equals(message.getMessageType())) {
            processResponse(output, message,
                    socket.getInetAddress().getHostAddress(),
                    socket.getPort());
            if (message.getParsedData().containsKey("position")) {
                processPosition(message,
                        socket.getInetAddress().getHostAddress(),
                        socket.getPort());
            }
        }
    }

    private void handleGt06Protocol(Socket clientSocket, DeviceMessage message,
                                    DeviceSession session, OutputStream output) {
        try {
            // Get or generate response
            byte[] response = (byte[]) message.getParsedData().get("response");
            if (response == null && "LOGIN".equals(message.getMessageType())) {
                // Generate login response if missing (matches Gt06Handler behavior)
                short serialNumber = extractSerialNumber(message);
                response = generateStandardGt06Response(PROTOCOL_LOGIN, serialNumber, (byte)0x01);
                message.getParsedData().put("response", response);
            }

            if (response != null && response.length > 0) {
                output.write(response);
                output.flush();
                logger.debug("Sent GT06 response to {}: {}",
                        session.getImei(), bytesToHex(response));
            }
        } catch (IOException e) {
            logger.error("Failed to send GT06 response to {}", session.getImei(), e);
        }
    }

    private byte[] generateGt06FallbackResponse(DeviceMessage message) {
        return ByteBuffer.allocate(11)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05) // Length
                .put(PROTOCOL_LOGIN)
                .put((byte) 0x00) // Default serial
                .put((byte) 0x00)
                .put((byte) 0x01) // Success
                .put((byte) 0x0D).put((byte) 0x0A)
                .array();
    }

    private void handleTeltonikaDataPhase(Socket socket, DeviceSession session) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        byte[] buffer = new byte[MAX_PACKET_SIZE];

        while (!socket.isClosed()) {
            int bytesRead = input.read(buffer);
            if (bytesRead == -1) break;

            byte[] data = Arrays.copyOf(buffer, bytesRead);
            DeviceMessage message = processProtocolMessage(data);

            if (message != null && PROTOCOL_TELTONIKA.equals(message.getProtocol())) {
                message.setImei(session.getImei());
                message.setProtocol(session.getProtocol());

                if (message.getParsedData().containsKey("position")) {
                    Position position = (Position) message.getParsedData().get("position");
                    if (position.getDevice() == null) {
                        Device device = new Device();
                        device.setImei(session.getImei());
                        device.setProtocolType(session.getProtocol());
                        position.setDevice(device);
                    }
                }

                processResponse(output, message,
                        socket.getInetAddress().getHostAddress(),
                        socket.getPort());

                if (message.getParsedData().containsKey("position")) {
                    processPosition(message,
                            socket.getInetAddress().getHostAddress(),
                            socket.getPort());
                }
            }
        }
    }

    private void handleGt06DataPhase(Socket socket, DeviceSession session) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        byte[] buffer = new byte[MAX_PACKET_SIZE];

        while (!socket.isClosed()) {
            int bytesRead = input.read(buffer);
            if (bytesRead == -1) break;

            byte[] data = Arrays.copyOf(buffer, bytesRead);
            DeviceMessage message = processProtocolMessage(data);

            if (message != null && PROTOCOL_GT06.equals(message.getProtocol())) {
                message.setImei(session.getImei());
                message.setProtocol(session.getProtocol());

                if (message.getParsedData().containsKey("position")) {
                    Position position = (Position) message.getParsedData().get("position");
                    if (position.getDevice() == null) {
                        Device device = new Device();
                        device.setImei(session.getImei());
                        device.setProtocolType(session.getProtocol());
                        position.setDevice(device);
                    }
                }

                processResponse(output, message,
                        socket.getInetAddress().getHostAddress(),
                        socket.getPort());

                if (message.getParsedData().containsKey("position")) {
                    processPosition(message,
                            socket.getInetAddress().getHostAddress(),
                            socket.getPort());
                }
            }
        }
    }

    private void cleanupConnection(Socket clientSocket, String reason) {
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                int clientPort = clientSocket.getPort();

                addressToSessionMap.remove(clientSocket.getRemoteSocketAddress());

                clientSocket.close();
                logger.info("Closed connection with {}:{} (Reason: {})",
                        clientAddress, clientPort, reason);
            } catch (IOException e) {
                logger.warn("Error closing socket", e);
            }
        }
    }

    private void trackConnection(DeviceMessage message, String clientAddress) {
        if (message == null || message.getImei() == null) {
            return;
        }

        String imei = message.getImei();
        DeviceConnection conn = activeConnections.compute(imei, (k, v) -> {
            if (v == null) {
                return new DeviceConnection(imei, clientAddress, System.currentTimeMillis(), 1);
            }
            v.connectionCount++;
            v.lastSeen = System.currentTimeMillis();
            return v;
        });

        if (conn.connectionCount > MAX_RECONNECTIONS_BEFORE_ALERT) {
            logger.warn("Frequent reconnections from IMEI: {} ({} times)", imei, conn.connectionCount);
        }

        if (conn.isSuspicious()) {
            logger.error("SUSPICIOUS CONNECTION PATTERN detected from IMEI: {} - {} connections in {} ms",
                    imei, conn.connectionCount, (conn.lastSeen - conn.firstSeen));
            conn.flagged = true;

            if (conn.connectionCount > SUSPICIOUS_CONNECTION_THRESHOLD * 2) {
                blacklistedIps.add(clientAddress);
                logger.warn("IP {} blacklisted due to suspicious activity", clientAddress);
            }
        }
    }

    private void processResponse(OutputStream output, DeviceMessage message,
                                 String clientAddress, int clientPort) {
        try {
            if (message == null || message.getParsedData() == null) {
                logger.warn("Invalid message structure from {}", clientAddress);
                return;
            }

            Object responseObj = message.getParsedData().get("response");
            if (responseObj == null) {
                logger.error("CRITICAL: No response generated for {}", clientAddress);
                return;
            }

            if (!(responseObj instanceof byte[])) {
                logger.error("Invalid response type from {}: {}",
                        clientAddress, responseObj.getClass().getSimpleName());
                return;
            }

            byte[] responseBytes = (byte[]) responseObj;
            output.write(responseBytes);
            output.flush();

            String messageType = message.getMessageType();
            if (messageType == null) {
                logger.warn("Message type not set for message from {}", clientAddress);
                return;
            }

            if ("ERROR".equals(messageType)) {
                logger.warn("Sent error response to {}:{} ({} bytes) - Error: {}",
                        clientAddress, clientPort, responseBytes.length,
                        message.getError() != null ? message.getError() : "Unknown error");
            } else {
                logger.info("Sent {} response to {}:{} ({} bytes)",
                        messageType,
                        clientAddress, clientPort,
                        responseBytes.length);
            }
        } catch (IOException e) {
            logger.error("Failed to send response to {}:{} - {}",
                    clientAddress, clientPort, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error processing response for {}:{} - {}",
                    clientAddress, clientPort, e.getMessage(), e);
        }
    }

    private void processPosition(DeviceMessage message, String clientAddress, int clientPort) {
        if (message.getParsedData() == null || !message.getParsedData().containsKey("position")) {
            logger.debug("No position data in message from {}:{}", clientAddress, clientPort);
            return;
        }

        Object positionObj = message.getParsedData().get("position");
        if (!(positionObj instanceof Position)) {
            logger.warn("Invalid position type from {}:{} - expected Position object",
                    clientAddress, clientPort);
            return;
        }

        Position position = (Position) positionObj;
        String imei = position.getDevice() != null ? position.getDevice().getImei() : message.getImei();

        if (imei == null) {
            logger.warn("Invalid position data from {}:{} - missing IMEI",
                    clientAddress, clientPort);
            return;
        }

        if (position.getDevice() == null) {
            Device device = new Device();
            device.setImei(imei);
            device.setProtocolType(message.getProtocol());
            position.setDevice(device);
        }

        logger.info("Processing position for device {}", imei);

        try {
            Position savedPosition = positionService.processAndSavePosition(position);
            if (savedPosition != null && savedPosition.getId() != null) {
                logger.debug("Successfully saved position ID {} for device {}",
                        savedPosition.getId(), imei);
                trackConnection(message, clientAddress);
            }
        } catch (Exception e) {
            logger.error("Failed to save position for device {}: {}", imei, e.getMessage(), e);
        }
    }

    private void handleUdpPacket(DatagramPacket packet) {
        String clientAddress = packet.getAddress().getHostAddress();
        int clientPort = packet.getPort();
        byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

        logUdpPacket(clientAddress, clientPort, data);

        DeviceMessage message = processProtocolMessage(data);
        if (message != null && message.getParsedData() != null) {
            DeviceSession session = sessionManager.getOrCreateSession(
                    message.getImei(),
                    message.getProtocol(),
                    packet.getSocketAddress());
            addressToSessionMap.put(packet.getSocketAddress(), session);

            if (PROTOCOL_TELTONIKA.equals(message.getProtocol())) {
                handleTeltonikaUdp(packet, message);
            } else if (PROTOCOL_GT06.equals(message.getProtocol())) {
                handleGt06Udp(packet, message);
            }
        }
    }

    private void handleTeltonikaUdp(DatagramPacket packet, DeviceMessage message) {
        if (message.getParsedData().containsKey("position")) {
            processUdpPosition(message.getParsedData().get("position"),
                    packet.getAddress().getHostAddress(),
                    packet.getPort(),
                    message);
        }
        sendUdpResponse(packet, message);
    }

    private void handleGt06Udp(DatagramPacket packet, DeviceMessage message) {
        if (message.getParsedData().containsKey("position")) {
            processUdpPosition(message.getParsedData().get("position"),
                    packet.getAddress().getHostAddress(),
                    packet.getPort(),
                    message);
        }
        sendUdpResponse(packet, message);
    }

    private void processUdpPosition(Object positionObj, String clientAddress,
                                    int clientPort, DeviceMessage message) {
        if (!(positionObj instanceof Position)) {
            logger.warn("Invalid UDP position type from {}:{}", clientAddress, clientPort);
            return;
        }

        Position position = (Position) positionObj;
        if (position.getDevice() == null || position.getDevice().getImei() == null) {
            logger.warn("Invalid UDP position data from {}:{} - missing device or IMEI",
                    clientAddress, clientPort);
            return;
        }

        String imei = position.getDevice().getImei();
        try {
            Position savedPosition = positionService.processAndSavePosition(position);
            if (savedPosition != null && savedPosition.getId() != null) {
                logger.info("Successfully saved UDP position ID {} for device {}",
                        savedPosition.getId(), imei);
                if (message != null) {
                    trackConnection(message, clientAddress);
                }
            } else {
                logger.error("UDP position save operation returned null or invalid position");
            }
        } catch (Exception e) {
            logger.error("Failed to save UDP position for device {}: {}", imei, e.getMessage(), e);
        }
    }

    private DeviceMessage processProtocolMessage(byte[] data) {
        if (data == null || data.length == 0) {
            logger.error("Null or empty data received");
            return createErrorResponse("INVALID_DATA", "Empty packet");
        }

        try {
            ProtocolDetector.ProtocolDetectionResult detection = protocolDetector.detect(data);
            DeviceMessage message;
            String protocol = detection.getProtocol();
            String packetType = detection.getPacketType();
            String version = detection.getVersion();

            logger.info("Detected protocol: {} (Type: {}, Version: {})",
                    protocol, packetType, version);

            // Explicit GT06 handling
            if (data.length >= 2 && data[0] == PROTOCOL_HEADER_1 && data[1] == PROTOCOL_HEADER_2) {
                return handleGt06Message(data);
            }


            // Handle Teltonika IMEI packets
            if ("TELTONIKA".equals(protocol) && "IMEI".equals(packetType)) {
                return handleTeltonikaImei(data);
            }

            if ("UNKNOWN".equals(protocol)) {
                logger.warn("Unrecognized protocol format");
                return createErrorResponse("UNSUPPORTED_PROTOCOL", "Protocol not recognized");
            }

            // Default handling
            return handleWithProtocolHandlers(data, protocol, packetType, version);
        } catch (Exception e) {
            logger.error("Protocol processing error", e);
            return createErrorResponse("PROCESSING_ERROR", e.getMessage());
        }
    }

    private boolean isGt06Packet(byte[] data) {
        return data.length >= 2 &&
                data[0] == PROTOCOL_HEADER_1 &&
                data[1] == PROTOCOL_HEADER_2;
    }

    private DeviceMessage handleGt06Message(byte[] data) {
        try {
            DeviceMessage message = gt06Handler.handle(data);

            // Special handling for login packets
            if ("LOGIN".equals(message.getMessageType())) {
                // Check for duplicate login
                Optional<DeviceSession> sessionOpt = sessionManager.getSession(message.getImei());
                short serialNumber = extractSerialNumber(message); // Changed from extractSerialNumberFromData

                if (sessionOpt.isPresent()) {
                    DeviceSession session = sessionOpt.get();
                    if (session.isDuplicateSerialNumber(serialNumber)) { // Changed from isDuplicateLogin
                        logger.warn("Duplicate GT06 login from IMEI: {}", message.getImei());
                        // Still respond to prevent device retries
                        byte[] response = generateStandardGt06Response( // Changed from generateLoginResponse
                                PROTOCOL_LOGIN, serialNumber, (byte)0x01);
                        message.getParsedData().put("response", response);
                    }
                }
            }

            return message;
        } catch (Exception e) {
            logger.error("GT06 processing error", e);
            return createErrorResponse("GT06_ERROR", e.getMessage());
        }
    }

    private byte[] generateStandardGt06Response(byte protocol, short serialNumber, byte status) {
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05; // Length
        response[3] = protocol;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);
        response[6] = status;

        // Calculate checksum
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum);
        response[9] = 0x0A;

        return response;
    }

    private DeviceMessage handleTeltonikaImei(byte[] data) {
        if (data.length < 4) {
            logger.error("Invalid Teltonika IMEI packet: too short ({} bytes)", data.length);
            return createErrorResponse("INVALID_IMEI", "Packet too short");
        }

        int declaredLength = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        if (data.length != declaredLength + 2) {
            logger.error("Invalid Teltonika IMEI length: declared {} but got {} bytes",
                    declaredLength, data.length - 2);
            return createErrorResponse("INVALID_IMEI", "Length mismatch");
        }

        if (data.length < 17) {
            logger.error("Incomplete IMEI data: need 15 bytes, got {}", data.length - 2);
            return createErrorResponse("INCOMPLETE_IMEI", "Need 15 IMEI digits");
        }

        try {
            String imei = new String(data, 2, 15, StandardCharsets.US_ASCII);
            if (!imei.matches("^\\d{15}$")) {
                logger.error("Invalid IMEI format: {}", imei);
                return createErrorResponse("INVALID_IMEI", "Must be 15 digits");
            }

            DeviceMessage message = new DeviceMessage();
            message.setProtocol(PROTOCOL_TELTONIKA);
            message.setImei(imei);
            message.setMessageType(PACKET_TYPE_IMEI);
            message.addParsedData("response", new byte[]{0x01}); // Standard accept response
            return message;
        } catch (Exception e) {
            logger.error("IMEI processing error: {}", e.getMessage());
            return createErrorResponse("IMEI_ERROR", e.getMessage());
        }
    }

    private DeviceMessage handleWithProtocolHandlers(byte[] data, String protocol,
                                                     String packetType, String version) {
        List<String> attemptedHandlers = new ArrayList<>();

        for (ProtocolHandler handler : protocolHandlers) {
            if (handler.canHandle(protocol, version)) {
                attemptedHandlers.add(handler.getClass().getSimpleName());

                try {
                    DeviceMessage message = handler.handle(data);
                    if (message != null) {
                        logger.debug("Successfully processed with {}",
                                handler.getClass().getSimpleName());
                        return message;
                    }
                } catch (ProtocolException e) {
                    logger.warn("Handler {} failed: {}",
                            handler.getClass().getSimpleName(), e.getMessage());
                    continue;
                }
            }
        }

        if (!attemptedHandlers.isEmpty()) {
            logger.error("All {} handlers failed for {} {} (v{}): {}",
                    attemptedHandlers.size(), protocol, packetType, version,
                    String.join(", ", attemptedHandlers));
        } else {
            logger.error("No handlers available for {} {} (v{})",
                    protocol, packetType, version);
        }

        return createErrorResponse("NO_HANDLER",
                "No available handler could process this message");
    }
    private DeviceMessage createErrorResponse(String errorCode, String errorMessage) {
        DeviceMessage errorMsg = new DeviceMessage();
        errorMsg.setMessageType("ERROR");
        errorMsg.addParsedData("errorCode", errorCode);
        errorMsg.addParsedData("errorMessage", errorMessage);
        return errorMsg;
    }

    private void logHexDump(byte[] data) {
        if (!logger.isDebugEnabled()) return;

        StringBuilder hexDump = new StringBuilder();
        StringBuilder asciiDump = new StringBuilder();

        for (int i = 0; i < data.length; i++) {
            hexDump.append(String.format("%02X ", data[i]));
            char c = (data[i] >= 32 && data[i] < 127) ? (char) data[i] : '.';
            asciiDump.append(c);

            if ((i + 1) % 16 == 0 || i == data.length - 1) {
                logger.debug("{}{} |{}|",
                        String.format("%04X: ", i - 15),
                        hexDump.toString(),
                        asciiDump.toString());
                hexDump.setLength(0);
                asciiDump.setLength(0);
            }
        }
    }

    private void logUdpPacket(String address, int port, byte[] data) {
        if (logger.isDebugEnabled()) {
            logger.debug("UDP packet from {}:{} ({} bytes):\n{}",
                    address, port, data.length, formatHexDump(data));
        } else {
            logger.info("UDP packet from {}:{} ({} bytes) - {}...",
                    address, port, data.length, bytesToHex(Arrays.copyOf(data, Math.min(data.length, 8))));
        }
    }

    private void sendUdpResponse(DatagramPacket receivedPacket, DeviceMessage message) {
        try {
            byte[] response = (byte[]) message.getParsedData().get("response");
            DatagramPacket responsePacket = new DatagramPacket(
                    response,
                    response.length,
                    receivedPacket.getAddress(),
                    receivedPacket.getPort()
            );

            udpSocket.send(responsePacket);
            logger.debug("Sent UDP response to {}:{} ({} bytes)",
                    receivedPacket.getAddress().getHostAddress(),
                    receivedPacket.getPort(),
                    response.length);
        } catch (IOException e) {
            logger.error("Failed to send UDP response", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private String formatHexDump(byte[] data) {
        StringBuilder output = new StringBuilder();
        int offset = 0;

        while (offset < data.length) {
            output.append(String.format("%04X: ", offset));

            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    output.append(String.format("%02X ", data[offset + i]));
                } else {
                    output.append("   ");
                }
                if (i == 7) output.append(" ");
            }

            output.append(" ");
            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    char c = (char) data[offset + i];
                    output.append(c >= 32 && c < 127 ? c : '.');
                }
            }

            offset += 16;
            output.append("\n");
        }

        return output.toString();
    }
    private boolean shouldThrottleConnection(String clientAddress) {
        ConnectionInfo info = connectionInfoMap.get(clientAddress);
        if (info == null) return false;

        long now = System.currentTimeMillis();
        if (now - info.lastActivity < 1000 && info.connectionCount > 5) {
            logger.warn("Throttling connection from {} - too frequent", clientAddress);
            return true;
        }
        return false;
    }
    public void handleDeviceMessage(DeviceMessage message) {
        if (message.isResponseRequired() && message.getResponseData() == null) {
            logger.error("No login response available for {} device {}",
                    message.getProtocol(), message.getImei());
            throw new IllegalStateException("Missing login response for " + message.getProtocol() + " device");
        }

        // Send response if available
        if (message.getResponseData() != null) {
            //channel.writeAndFlush(Unpooled.copiedBuffer(message.getResponseData()));
        }
    }


    private static class ConnectionInfo {
        long lastActivity;
        int connectionCount;
        SocketAddress remoteAddress;

        ConnectionInfo(SocketAddress remoteAddress) {
            this.remoteAddress = remoteAddress;
            this.lastActivity = System.currentTimeMillis();
            this.connectionCount = 1;
        }
    }

    private class DeviceConnection {
        final String imei;
        final String ip;
        long lastSeen;
        int connectionCount;
        long firstSeen;
        boolean flagged;

        public DeviceConnection(String imei, String ip, long lastSeen, int connectionCount) {
            this.imei = imei;
            this.ip = ip;
            this.lastSeen = lastSeen;
            this.connectionCount = connectionCount;
            this.firstSeen = System.currentTimeMillis();
            this.flagged = false;
        }

        public boolean isSuspicious() {
            long connectionInterval = lastSeen - firstSeen;
            return connectionCount > SUSPICIOUS_CONNECTION_THRESHOLD &&
                    connectionInterval < SUSPICIOUS_TIME_WINDOW;
        }
    }

    public ServerStats getStats() {
        return new ServerStats(
                isRunning(),
                tcpPort,
                bossGroup != null ? 1 : 0, // Boss group always has 1 thread
                workerGroup != null ? workerThreads : 0, // Use configured worker threads
                sessionManager.getActiveSessionCount()
        );
    }

    public static class ServerStats {
        private final boolean running;
        private final int port;
        private final int bossThreads;
        private final int workerThreads;
        private final int activeConnections;

        public ServerStats(boolean running, int port, int bossThreads,
                           int workerThreads, int activeConnections) {
            this.running = running;
            this.port = port;
            this.bossThreads = bossThreads;
            this.workerThreads = workerThreads;
            this.activeConnections = activeConnections;
        }

        // Getters
        public boolean isRunning() { return running; }
        public int getPort() { return port; }
        public int getBossThreads() { return bossThreads; }
        public int getWorkerThreads() { return workerThreads; }
        public int getActiveConnections() { return activeConnections; }

        @Override
        public String toString() {
            return String.format(
                    "ServerStats[running=%s, port=%d, bossThreads=%d, workerThreads=%d, activeConnections=%d]",
                    running, port, bossThreads, workerThreads, activeConnections
            );
        }
    }

    private boolean isRunning() {
        return running.get() &&
                (serverChannel != null && serverChannel.isActive()) ||
                (tcpServerSocket != null && !tcpServerSocket.isClosed());
    }
}