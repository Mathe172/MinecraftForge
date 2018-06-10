package net.minecraftforge.common.lighting;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;
import net.minecraft.util.math.BlockPos.PooledMutableBlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.lighting.network.NewLightPacketHandler;
import net.minecraftforge.common.lighting.network.SPacketLightTracking;

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

    private static void clearIncomingTrackingData(final PlayerChunkMapEntry chunk, final int sectionMask)
    {
        clearTrackingData(chunk.lightTrackingTick, sectionMask);
    }

    private static void clearOutgoingTrackingData(final PlayerChunkMap chunkMap, final int sectionMask)
    {
        clearTrackingData(chunkMap.neighborChunksCache, sectionMask);
    }

    private static void clearTrackingData(final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap, final int sectionMask)
    {
        clearIncomingTrackingData(chunk, sectionMask);
        clearOutgoingTrackingData(chunkMap, sectionMask);
    }

    private static void clearTrackingData(final PlayerChunkMapEntry[] chunks, final int sectionMask)
    {
        for (int i = 0; i < 6; ++i)
        {
            final PlayerChunkMapEntry chunk = chunks[i];

            if (chunk != null && !chunk.lightTrackingEmpty)
                clearTrackingData(EnumFacing.VALUES[i], chunk.lightTrackingTick, sectionMask);
        }
    }

    private static void clearTrackingData(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final int sectionMask)
    {
        final long[] data = chunk.lightTrackingData.get(player);

        if (data == null)
            return;

        clearTrackingData(data, sectionMask);

        if (chunk.lightTrackingEmpty && isTrackingTrivial(data))
            chunk.lightTrackingData.remove(player);
    }

    private static void clearTrackingData(final long[] data, final int sectionMask)
    {
        for (final EnumFacing dir : EnumFacing.VALUES)
            clearTrackingData(dir, data, sectionMask);
    }

    private static void clearTrackingData(final EnumFacing dir, final long[] data, int sectionMask)
    {
        final int offset = getOffset(dir);

        if (dir == EnumFacing.UP)
            sectionMask >>>= 1;
        else if (dir == EnumFacing.DOWN)
            sectionMask &= (1 << 15) - 1;

        final int shift = dir.getAxis() == Axis.Y ? 15 : 16;

        final long removeMask = (((long) sectionMask << shift) | (long) sectionMask) << (offset & 63);

        data[offset >> 6] &= ~removeMask;
    }

    private static void moveTrackingData(
        final long[] data,
        final EnumFacing dir,
        final EntityPlayerMP player,
        final PlayerChunkMapEntry neighborChunk,
        final int sectionMask
    )
    {
        final long[] neighborData = neighborChunk.lightTrackingData.get(player);

        if (neighborData == null)
            copyTrackingData(dir, neighborChunk.lightTrackingTick, data, sectionMask, false);
        else
        {
            copyTrackingData(dir, neighborData, data, sectionMask, true);

            if (neighborChunk.lightTrackingEmpty && isTrackingTrivial(neighborData))
                neighborChunk.lightTrackingData.remove(player);
        }
    }

    private static void copyTrackingData(final EnumFacing dir, final long[] fromData, final long[] toData, int sectionMask, final boolean delete)
    {
        final int offset = getOffset(dir);

        if (dir == EnumFacing.DOWN)
            sectionMask >>>= 1;
        else if (dir == EnumFacing.UP)
            sectionMask &= (1 << 15) - 1;

        final int shift = dir.getAxis() == Axis.Y ? 15 : 16;

        final long copyMask = (((long) sectionMask << shift) | (long) sectionMask) << (offset & 63);

        toData[offset >> 6] |= fromData[offset >> 6] & copyMask;

        if (delete)
            fromData[offset >> 6] &= ~copyMask;
    }

    private static long[] collectTrackingData(final EntityPlayerMP player, final int sectionMask, final PlayerChunkMap chunkMap)
    {
        return collectTrackingData(player, sectionMask, chunkMap.neighborChunksCache);
    }

    private static long[] collectTrackingData(final EntityPlayerMP player, final int sectionMask, final PlayerChunkMapEntry[] chunks)
    {
        final long[] data = new long[3];

        for (int i = 0; i < 6; ++i)
        {
            final PlayerChunkMapEntry chunk = chunks[i];

            if (chunk != null)
                moveTrackingData(data, EnumFacing.VALUES[i], player, chunk, sectionMask);
        }

        return data;
    }

    private static void prepareNeighborChunks(final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap)
    {
        final ChunkPos pos = chunk.getPos();

        for (int i = 0; i < 6; ++i)
        {
            final EnumFacing dir = EnumFacing.VALUES[i];

            final PlayerChunkMapEntry neighborChunk = dir.getAxis() == Axis.Y
                ? chunk
                : chunkMap.getEntry(pos.x + dir.getFrontOffsetX(), pos.z + dir.getFrontOffsetZ());

            chunkMap.neighborChunksCache[i] = neighborChunk;

            if (neighborChunk != null)
                applyTrackings(neighborChunk);
        }
    }

    public static void onSendChunkToPlayers(final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap, final List<EntityPlayerMP> players)
    {
        final int sectionMask = (1 << 16) - 1;

        prepareNeighborChunks(chunk, chunkMap);

        for (final EntityPlayerMP player : players)
        {
            clearTrackingData(player, chunk, sectionMask);
            final long[] data = collectTrackingData(player, sectionMask, chunkMap);

            if (!isTrackingTrivial(data))
                NewLightPacketHandler.INSTANCE.sendTo(new SPacketLightTracking(chunk.getPos(), data), player);
        }

        clearTrackingData(chunk, chunkMap, sectionMask);
        Arrays.fill(chunkMap.neighborChunksCache, null);
    }

    public static void onSendChunkToPlayer(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap)
    {
        final int sectionMask = (1 << 16) - 1;

        prepareNeighborChunks(chunk, chunkMap);

        clearTrackingData(player, chunk, sectionMask);
        final long[] data = collectTrackingData(player, sectionMask, chunkMap);

        if (!isTrackingTrivial(data))
            NewLightPacketHandler.INSTANCE.sendTo(new SPacketLightTracking(chunk.getPos(), data), player);

        Arrays.fill(chunkMap.neighborChunksCache, null);
    }

    public static void onUpdateChunk(final PlayerChunkMapEntry chunk, final int sectionMask)
    {
        chunk.lightTrackingSectionMask |= sectionMask;
        applyTrackings(chunk);

        clearIncomingTrackingData(chunk, sectionMask);

        for (final Iterator<long[]> it = chunk.lightTrackingData.values().iterator(); it.hasNext(); )
        {
            final long[] data = it.next();

            clearTrackingData(data, sectionMask);

            if (chunk.lightTrackingEmpty && isTrackingTrivial(data))
                it.remove();
        }
    }

    public static void onChunkMapTick(final PlayerChunkMap chunkMap, final Collection<PlayerChunkMapEntry> chunks)
    {
        for (final PlayerChunkMapEntry chunk : chunks)
        {
            if (chunk.lightTrackingSectionMask == 0)
                continue;

            prepareNeighborChunks(chunk, chunkMap);

            for (final EntityPlayerMP player : chunk.players)
            {
                final long[] data = collectTrackingData(player, chunk.lightTrackingSectionMask, chunkMap);

                if (!isTrackingTrivial(data))
                    NewLightPacketHandler.INSTANCE.sendTo(new SPacketLightTracking(chunk.getPos(), data), player);
            }

            clearOutgoingTrackingData(chunkMap, chunk.lightTrackingSectionMask);
            chunk.lightTrackingSectionMask = 0;
        }

        Arrays.fill(chunkMap.neighborChunksCache, null);
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
