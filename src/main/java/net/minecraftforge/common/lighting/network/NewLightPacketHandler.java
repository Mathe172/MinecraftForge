package net.minecraftforge.common.lighting.network;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class NewLightPacketHandler
{
    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel("newlight");

    public static void registerMessages()
    {
        INSTANCE.registerMessage(SPacketLightTracking.Handler.class, SPacketLightTracking.class, 0, Side.CLIENT);
    }
}
