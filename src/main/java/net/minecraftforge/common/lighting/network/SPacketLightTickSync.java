package net.minecraftforge.common.lighting.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class SPacketLightTickSync implements IMessage
{
    public static final SPacketLightTickSync INSTANCE = new SPacketLightTickSync();

    @Override
    public void fromBytes(final ByteBuf buf)
    {
    }

    @Override
    public void toBytes(final ByteBuf buf)
    {
    }

    public static class Handler implements IMessageHandler<SPacketLightTickSync, IMessage>
    {
        @Override
        public IMessage onMessage(final SPacketLightTickSync message, final MessageContext ctx)
        {
            Minecraft.getMinecraft().addScheduledTask(() -> Minecraft.getMinecraft().world.lightingEngine.procLightUpdates());

            return null;
        }
    }
}
