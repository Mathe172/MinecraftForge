package net.minecraftforge.common.lighting.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class SPacketLightTracking implements IMessage
{
    public int chunkX;
    public int chunkZ;
    public long[] data;

    public SPacketLightTracking()
    {
    }

    public SPacketLightTracking(final int chunkX, final int chunkZ, final long[] data)
    {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.data = data;
    }

    public SPacketLightTracking(final ChunkPos pos, final long[] data)
    {
        this(pos.x, pos.z, data);
    }

    @Override
    public void fromBytes(final ByteBuf buf)
    {
        this.chunkX = buf.readInt();
        this.chunkZ = buf.readInt();

        this.data = new long[3];

        for (int i = 0; i < 3; ++i)
            this.data[i] = buf.readLong();
    }

    @Override
    public void toBytes(final ByteBuf buf)
    {
        buf.writeInt(this.chunkX).writeInt(this.chunkZ);

        for (final long l : this.data)
            buf.writeLong(l);
    }

    public static class Handler implements IMessageHandler<SPacketLightTracking, IMessage>
    {
        @Override
        public IMessage onMessage(final SPacketLightTracking message, final MessageContext ctx)
        {
            return null;
        }
    }
}
