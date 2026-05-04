package org.geysermc.geyser.network.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

/**
 * Adapts Nethernet (WebRTC) framing to the RakMessage format expected by
 * CloudburstMC's Bedrock protocol pipeline.
 *
 * Nethernet delivers raw ByteBufs without the 0xFE game packet prefix.
 * The Bedrock pipeline (FrameIdCodec) expects inbound RakMessages with
 * the 0xFE prefix and produces outbound ByteBufs with the 0xFE prefix.
 *
 * Inbound (client to server): ByteBuf -> prepend 0xFE -> wrap as RakMessage
 * Outbound (server to client): ByteBuf with 0xFE -> strip 0xFE -> write raw ByteBuf
 */
public class NetherNetFramingAdapter extends ChannelDuplexHandler {

    public static final String NAME = "nethernet-framing-adapter";
    private static final byte GAME_PACKET_ID = (byte) 0xFE;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf clientBuf)) {
            ReferenceCountUtil.release(msg);
            return;
        }

        if (!clientBuf.isReadable()) {
            clientBuf.release();
            return;
        }

        // Prepend 0xFE and wrap as RakMessage for the Bedrock pipeline
        ByteBuf framed = ctx.alloc().buffer(1 + clientBuf.readableBytes());
        framed.writeByte(GAME_PACKET_ID);
        framed.writeBytes(clientBuf);
        clientBuf.release();

        ctx.fireChannelRead(new RakMessage(framed));
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf serverBuf)) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
            return;
        }

        if (serverBuf.readableBytes() < 1) {
            serverBuf.release();
            promise.setSuccess();
            return;
        }

        // FrameIdCodec.encode() outputs a ByteBuf starting with 0xFE.
        // Strip the prefix and write the raw payload to the Nethernet channel.
        // Must use a slice because the kastle Nethernet writer reads from
        // absolute index 0, not readerIndex.
        byte frameId = serverBuf.readByte();
        if (frameId != GAME_PACKET_ID) {
            // Unexpected - not a game packet. Write as-is.
            serverBuf.resetReaderIndex();
            ctx.write(serverBuf, promise);
            return;
        }

        ByteBuf payload = serverBuf.readRetainedSlice(serverBuf.readableBytes());
        serverBuf.release();
        ctx.write(payload, promise);
    }
}
