package org.geysermc.geyser.network.nethernet;

import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nethernet (WebRTC) server that accepts incoming connections from clients
 * using a connection ID. Connections pipe directly into Geyser's session
 * handling via the Bedrock protocol pipeline.
 *
 * Owns the full lifecycle: PlayFab MCToken acquisition, signaling WebSocket
 * management, periodic health checks, and automatic reconnection.
 */
public class NetherNetServer {

    private static final long SIGNALING_CHECK_INTERVAL_SECONDS = 120;
    private static final String LOG_PREFIX = "[Nethernet] ";

    private final GeyserImpl geyser;
    private final GeyserLogger logger;
    private final DefaultEventLoopGroup playerEventLoopGroup;
    private final PlayFabTokenManager tokenManager;
    private final String connectionId;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private NetherNetXboxSignaling signaling;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> signalingCheckTask;
    private volatile boolean running;

    public NetherNetServer(GeyserImpl geyser, DefaultEventLoopGroup playerEventLoopGroup, String connectionId) {
        this.geyser = geyser;
        this.logger = geyser.getLogger();
        this.playerEventLoopGroup = playerEventLoopGroup;
        this.tokenManager = new PlayFabTokenManager(logger);
        this.connectionId = connectionId;
    }

    /**
     * Starts the Nethernet server. Acquires an MCToken via PlayFab,
     * opens a signaling WebSocket, and begins accepting WebRTC connections.
     *
     * @return true if the server started successfully
     */
    public synchronized boolean start() {
        if (running) {
            return true;
        }

        String mcToken = tokenManager.authenticate();
        if (mcToken == null) {
            logger.error(LOG_PREFIX + "Failed to obtain MCToken from PlayFab");
            return false;
        }

        if (!bind(mcToken)) {
            shutdown();
            return false;
        }

        // Start signaling health check
        scheduler = Executors.newSingleThreadScheduledExecutor();
        signalingCheckTask = scheduler.scheduleAtFixedRate(this::checkSignaling,
                SIGNALING_CHECK_INTERVAL_SECONDS, SIGNALING_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        running = true;
        logger.info(LOG_PREFIX + "Listening on connection ID: " + connectionId);
        return true;
    }

    /**
     * Rebuilds the signaling WebSocket with a fresh MCToken, preserving the
     * same connection ID. Existing WebRTC peer connections are unaffected.
     *
     * @return true if the signaling reconnected successfully
     */
    public synchronized boolean restartSignaling() {
        if (!running) {
            return start();
        }

        String mcToken = tokenManager.authenticate();
        if (mcToken == null) {
            logger.warning(LOG_PREFIX + "Signaling rebuild failed: could not get MCToken");
            return false;
        }

        closeChannel();
        if (bind(mcToken)) {
            logger.info(LOG_PREFIX + "Signaling rebuilt successfully");
            return true;
        } else {
            logger.warning(LOG_PREFIX + "Signaling rebuild failed, shutting down");
            shutdown();
            return false;
        }
    }

    /**
     * Shuts down the Nethernet server and releases all resources.
     */
    public synchronized void shutdown() {
        running = false;
        if (signalingCheckTask != null) {
            signalingCheckTask.cancel(false);
            signalingCheckTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        closeChannel();
    }

    public boolean isRunning() {
        return running && serverChannel != null && serverChannel.isActive();
    }

    public boolean isSignalingAlive() {
        return signaling != null && signaling.isChannelAlive();
    }

    public String getConnectionId() {
        return connectionId;
    }

    private boolean bind(String mcToken) {
        PeerConnectionFactory factory = new PeerConnectionFactory();
        this.signaling = new NetherNetXboxSignaling(connectionId, mcToken);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup(2);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channelFactory(NetherNetChannelFactory.server(factory, signaling))
                    .childHandler(new NetherNetServerInitializer(geyser, playerEventLoopGroup));
            this.serverChannel = bootstrap.bind(new InetSocketAddress(0)).sync().channel();
            return true;
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Failed to bind: " + e.getMessage());
            try { factory.dispose(); } catch (Exception ignored) {}
            this.signaling = null;
            if (bossGroup != null) { bossGroup.shutdownGracefully(); bossGroup = null; }
            if (workerGroup != null) { workerGroup.shutdownGracefully(); workerGroup = null; }
            return false;
        }
    }

    private void closeChannel() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        signaling = null;
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
    }

    private void checkSignaling() {
        if (!running) return;
        if (isSignalingAlive()) return;

        logger.info(LOG_PREFIX + "Signaling dead, rebuilding...");
        restartSignaling();
    }
}
