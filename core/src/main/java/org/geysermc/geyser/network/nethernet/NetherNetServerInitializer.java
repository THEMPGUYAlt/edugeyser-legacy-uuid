package org.geysermc.geyser.network.nethernet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.codec.FrameIdCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.GeyserBedrockPeer;
import org.geysermc.geyser.network.GeyserServerInitializer;
import org.geysermc.geyser.network.netty.BedrockEncryptionControl;

/**
 * Channel initializer for incoming Nethernet (WebRTC) connections.
 * Mirrors the CloudburstMC BedrockChannelInitializer pipeline for
 * rak protocol version 11, but without reading RakChannelOption from
 * the channel (Nethernet channels don't have RakNet options).
 *
 * The pipeline is:
 * NetherNetFramingAdapter -> FrameIdCodec -> CompressionCodec ->
 * BedrockBatchDecoder/Encoder -> BedrockPacketCodec_v3 -> GeyserBedrockPeer
 */
public class NetherNetServerInitializer extends ChannelInitializer<Channel> {

    private static final FrameIdCodec FRAME_CODEC = new FrameIdCodec(0xFE);
    private static final BedrockBatchDecoder BATCH_DECODER = new BedrockBatchDecoder();

    private final GeyserImpl geyser;
    private final DefaultEventLoopGroup eventLoopGroup;

    public NetherNetServerInitializer(GeyserImpl geyser, DefaultEventLoopGroup eventLoopGroup) {
        this.geyser = geyser;
        this.eventLoopGroup = eventLoopGroup;
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
        // Disable Bedrock encryption - Nethernet uses DTLS for transport encryption
        BedrockEncryptionControl.disableEncryption(channel);

        // Set packet direction for server-side codec
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);

        // Build the Bedrock pipeline mirroring CloudburstMC's init for rak version 11
        channel.pipeline()
                .addLast(NetherNetFramingAdapter.NAME, new NetherNetFramingAdapter())
                .addLast(FrameIdCodec.NAME, FRAME_CODEC)
                .addLast(CompressionCodec.NAME, new CompressionCodec(
                        new SimpleCompressionStrategy(new NoopCompression()), false))
                .addLast(BedrockBatchDecoder.NAME, BATCH_DECODER)
                .addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder())
                .addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3())
                .addLast(BedrockPeer.NAME, new GeyserBedrockPeer(channel, this::createSession));
    }

    private BedrockServerSession createSession(BedrockPeer peer, int subClientId) {
        BedrockServerSession session = new BedrockServerSession(peer, subClientId);
        try {
            GeyserServerInitializer.initGeyserSession(session, geyser, eventLoopGroup);
        } catch (Throwable e) {
            geyser.getLogger().error("Error occurred while initializing Nethernet player!", e);
            session.disconnect(e.getMessage());
        }
        return session;
    }
}
