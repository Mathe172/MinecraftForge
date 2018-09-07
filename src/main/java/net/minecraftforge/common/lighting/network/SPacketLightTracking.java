/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.common.lighting.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.common.lighting.LightTrackingHooks;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class SPacketLightTracking implements IMessage
{
    public int chunkX;
    public int chunkZ;
    public int[] data;

    public SPacketLightTracking()
    {
    }

    public SPacketLightTracking(final int chunkX, final int chunkZ, final int[] data)
    {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.data = data;
    }

    public SPacketLightTracking(final ChunkPos pos, final int[] data)
    {
        this(pos.x, pos.z, data);
    }

    @Override
    public void fromBytes(final ByteBuf buf)
    {
        this.chunkX = buf.readInt();
        this.chunkZ = buf.readInt();

        this.data = new int[LightTrackingHooks.SYNC_FLAG_COUNT];

        for (int i = 0; i < this.data.length; ++i)
            this.data[i] = buf.readInt();
    }

    @Override
    public void toBytes(final ByteBuf buf)
    {
        buf.writeInt(this.chunkX).writeInt(this.chunkZ);

        for (final int i : this.data)
            buf.writeInt(i);
    }

    public static class Handler implements IMessageHandler<SPacketLightTracking, IMessage>
    {
        @Override
        public IMessage onMessage(final SPacketLightTracking message, final MessageContext ctx)
        {
            Minecraft.getMinecraft().addScheduledTask(
                () -> LightTrackingHooks.applySyncLightTrackins(message.data, message.chunkX, message.chunkZ, Minecraft.getMinecraft().world)
            );

            return null;
        }
    }
}
