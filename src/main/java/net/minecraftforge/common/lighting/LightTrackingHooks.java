package net.minecraftforge.common.lighting;

import java.util.Arrays;
import java.util.Iterator;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.EnumSkyBlock;

public class LightTrackingHooks
{
    public static final int VERTICAL_OFFSET = 2 * 4 * 16;
    public static final int CHUNK_COUNT_OFFSET = VERTICAL_OFFSET + 2 * 2 * 15;

    public static void trackLightUpdate(final PlayerChunkMapEntry targetChunk, final PlayerChunkMap chunkMap, final int y, final EnumSkyBlock lightType, final EnumFacing dir)
    {
        trackLightUpdates(targetChunk, chunkMap, 1 << (y >> 4), lightType, dir);
    }

    public static void trackLightUpdates(final PlayerChunkMapEntry targetChunk, final PlayerChunkMap chunkMap, int targetSectionMask, final EnumSkyBlock lightType, final EnumFacing dir)
    {
        final int offset = LightTrackingHooks.getOffset(dir, lightType);

        if (dir == EnumFacing.UP)
            targetSectionMask >>>= 1;

        final long updateMask = ((long) targetSectionMask << (offset & 63));

        targetChunk.lightTrackingTick[offset >> 6] |= updateMask;
        targetChunk.lightTrackingAdd[offset >> 6] |= updateMask;

        if (targetChunk.lightTrackingEmpty)
        {
            targetChunk.lightTrackingEmpty = false;
            chunkMap.lightTrackingEntries.add(targetChunk);
        }
    }

    public static int getOffset(final EnumFacing dir)
    {
        return dir.getAxis() == Axis.Y ? VERTICAL_OFFSET + dir.getIndex() * 30 : (dir.getHorizontalIndex() * 32);
    }

    public static int getOffset(final EnumFacing dir, final EnumSkyBlock lightType)
    {
        return dir.getAxis() == Axis.Y
            ? VERTICAL_OFFSET + dir.getIndex() * 30 + LightUtils.getIndex(lightType) * 15
            : (dir.getHorizontalIndex() * 32 + LightUtils.getIndex(lightType) * 16);
    }

    private static void addMissingNeighbors(final long[] data, final int num)
    {
        data[CHUNK_COUNT_OFFSET >> 6] += ((long) num << (CHUNK_COUNT_OFFSET & 63));
    }

    public static boolean isTrackingTrivial(final long[] data)
    {
        for (final long l : data)
            if (l != 0)
                return false;

        return true;
    }

    public static long[] createTrackingData(final int missingNeighbors)
    {
        final long[] data = new long[3];
        addMissingNeighbors(data, missingNeighbors);
        return data;
    }

    public static boolean canPlayerSeeChunk(final EntityPlayerMP player, final PlayerChunkMapEntry chunk)
    {
        return chunk.isSentToPlayers() && chunk.containsPlayer(player);
    }

    public static void addPlayer(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap)
    {
        if (!chunk.isSentToPlayers())
            return;

        int neighborCount = 0;

        final ChunkPos pos = chunk.getPos();

        for (int x = -1; x <= 1; ++x)
        {
            for (int z = -1; z <= 1; ++z)
            {
                if (x == 0 && z == 0)
                    continue;

                final PlayerChunkMapEntry neighborChunk = chunkMap.getEntry(pos.x + x, pos.z + z);

                if (neighborChunk != null && canPlayerSeeChunk(player, neighborChunk))
                {
                    ++neighborCount;
                    addNeighbor(player, neighborChunk);
                }
            }
        }

        if (neighborCount < 8 || !chunk.lightTrackingEmpty)
            addTrackingEntry(player, chunk, 8 - neighborCount);
    }

    public static void addNeighbor(final EntityPlayerMP player, final PlayerChunkMapEntry chunk)
    {
        final long[] data = chunk.lightTrackingData.get(player);

        if (data == null)
            return;

        addMissingNeighbors(data, -1);

        if (chunk.lightTrackingEmpty && isTrackingTrivial(data))
            chunk.lightTrackingData.remove(player);
    }

    public static void removePlayer(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap)
    {
        if (!chunk.isSentToPlayers())
            return;

        final ChunkPos pos = chunk.getPos();

        for (int x = -1; x <= 1; ++x)
        {
            for (int z = -1; z <= 1; ++z)
            {
                if (x == 0 && z == 0)
                    continue;

                final PlayerChunkMapEntry neighborChunk = chunkMap.getEntry(pos.x + x, pos.z + z);

                if (neighborChunk != null)
                    removeNeighbor(player, neighborChunk);
            }
        }

        chunk.lightTrackingData.remove(player);
    }

    public static void removeNeighbor(final EntityPlayerMP player, final PlayerChunkMapEntry chunk)
    {
        long[] data = chunk.lightTrackingData.get(player);

        if (data == null)
        {
            if (!canPlayerSeeChunk(player, chunk))
                return;

            data = createTrackingData(1);
            for (int i = 0; i < data.length; ++i)
                data[i] |= chunk.lightTrackingTick[i];

            addTrackingEntry(player, chunk, data);
        }
        else
            addMissingNeighbors(data, 1);
    }

    public static void tick(final PlayerChunkMap chunkMap)
    {
        for (final PlayerChunkMapEntry chunk : chunkMap.lightTrackingEntries)
            cleanupTrackingTick(chunk);

        chunkMap.lightTrackingEntries.clear();
    }

    public static void cleanupTrackingTick(final PlayerChunkMapEntry chunk)
    {
        if (chunk.lightTrackingEmpty)
            return;

        chunk.lightTrackingEmpty = true;

        applyTrackings(chunk);

        for (final Iterator<long[]> it = chunk.lightTrackingData.values().iterator(); it.hasNext(); )
            if (isTrackingTrivial(it.next()))
                it.remove();

        Arrays.fill(chunk.lightTrackingTick, 0);
    }

    public static void addTrackingEntry(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final int missingNeighbors)
    {
        addTrackingEntry(player, chunk, createTrackingData(missingNeighbors));
    }

    public static void addTrackingEntry(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final long[] data)
    {
        applyTrackings(chunk);
        chunk.lightTrackingData.put(player, data);
    }

    public static void applyTrackings(final PlayerChunkMapEntry chunk)
    {
        if (chunk.lightTrackingEmpty || isTrackingTrivial(chunk.lightTrackingAdd))
            return;

        for (final long[] data : chunk.lightTrackingData.values())
        {
            for (int i = 0; i < data.length; ++i)
                data[i] |= chunk.lightTrackingAdd[i];
        }

        Arrays.fill(chunk.lightTrackingAdd, 0);
    }
}
