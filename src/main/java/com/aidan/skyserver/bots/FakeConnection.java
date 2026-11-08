package com.aidan.skyserver.bots;

import com.aidan.skyserver.Skyserver;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * A network connection with nobody on the other end, for bots. Packets the server sends to the
 * bot go into a dummy channel and are thrown away every tick.
 */
final class FakeConnection extends Connection {
    private final EmbeddedChannel dummy = new EmbeddedChannel();

    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // The channel and address fields are private; fill them in by type so this keeps
        // working even if Mojang renames them.
        setFieldOfType(Channel.class, dummy);
        setFieldOfType(SocketAddress.class, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /** Throws away everything the server sent to the bot since last tick. */
    void drain() {
        dummy.releaseOutbound();
        dummy.releaseInbound();
    }

    private void setFieldOfType(Class<?> type, Object value) {
        for (Class<?> c = Connection.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == type) {
                    try {
                        f.setAccessible(true);
                        f.set(this, value);
                        return;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        Skyserver.LOGGER.error("Bots: couldn't set up the fake connection ({})", type.getSimpleName(), e);
                    }
                }
            }
        }
        Skyserver.LOGGER.error("Bots: no {} field found in Connection", type.getSimpleName());
    }

    // There is no real client, so these all do nothing.

    @Override
    public void setReadOnly() {}

    @Override
    public void handleDisconnection() {}

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {}

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {}
}
