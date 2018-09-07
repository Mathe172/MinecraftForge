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

package net.minecraftforge.common.lighting;

import java.util.Arrays;
import java.util.Map.Entry;
import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.lighting.LightBoundaryCheckHooks.EnumBoundaryFacing;
import net.minecraftforge.common.lighting.network.NewLightPacketHandler;
import net.minecraftforge.common.lighting.network.SPacketLightTracking;

public class LightTrackingHooks
{
    private static final int NEIGHBOR_FLAG_COUNT = 12;
    private static final int PLAYER_NEIGHBOR_FLAG_COUNT = NEIGHBOR_FLAG_COUNT + 1;
    private static final int FLAG_COUNT = NEIGHBOR_FLAG_COUNT + 2;
    public static final int SYNC_FLAG_COUNT = LightBoundaryCheckHooks.FLAG_COUNT_CLIENT + NEIGHBOR_FLAG_COUNT;

    private static final int[] VERTICAL_MASKS = new int[2];

    static
    {
        VERTICAL_MASKS[0] = (1 << 16) - 2;
        VERTICAL_MASKS[1] = (1 << 15) - 1;
    }

    static int getHorizontalFlagIndex(final EnumFacing dir)
    {
        return dir.getHorizontalIndex() * 3 + 1;
    }

    public static void trackLightChange(final Chunk chunk, final BlockPos pos, final EnumSkyBlock lightType)
    {
        trackLightChangeHorizontal(chunk, pos, lightType);
        trackLightChangeVertical(chunk, pos, lightType);
    }

    public static void trackLightChangeHorizontal(final Chunk chunk, final BlockPos pos, final EnumSkyBlock lightType)
    {
        trackLightChangesHorizontal(chunk, pos.getX(), pos.getZ(), 1 << (pos.getY() >> 4), lightType);
    }

    public static void trackLightChangesHorizontal(final Chunk chunk, final int x, final int z, final int sectionMask, final EnumSkyBlock lightType)
    {
        final int xRegion = LightUtils.getBoundaryRegion(x & 15);
        final int zRegion = LightUtils.getBoundaryRegion(z & 15);

        if (xRegion != 0)
        {
            final EnumFacing dir = EnumFacing.HORIZONTALS[2 + xRegion];

            final int index = getHorizontalFlagIndex(dir) - LightUtils.getBoundaryRegionSigned(zRegion, xRegion, 0);

            trackLightChanges(chunk, index, sectionMask, lightType);
        }

        if (zRegion != 0)
        {
            final EnumFacing dir = EnumFacing.HORIZONTALS[1 - zRegion];

            final int index = getHorizontalFlagIndex(dir) - LightUtils.getBoundaryRegionSigned(xRegion, 0, zRegion);

            trackLightChanges(chunk, index, sectionMask, lightType);
        }
    }

    public static void trackLightChangeVertical(final Chunk chunk, final BlockPos pos, final EnumSkyBlock lightType)
    {
        final int y = pos.getY();

        final int region = LightUtils.getBoundaryRegion(y & 15);

        if (region != 0)
        {
            final int dirIndex = ((region + 1) >> 1);
            final int index = NEIGHBOR_FLAG_COUNT + dirIndex;

            trackLightChanges(chunk, index, (1 << (y >> 4)) & VERTICAL_MASKS[dirIndex], lightType);
        }
    }

    public static void trackLightChangesVertical(final Chunk chunk, final int sectionMask, final EnumSkyBlock lightType)
    {
        trackLightChanges(chunk, NEIGHBOR_FLAG_COUNT, sectionMask & VERTICAL_MASKS[0], lightType);
        trackLightChanges(chunk, NEIGHBOR_FLAG_COUNT + 1, sectionMask & VERTICAL_MASKS[1], lightType);
    }

    private static void trackLightChanges(final Chunk chunk, final int index, final int sectionMask, final EnumSkyBlock lightType)
    {
        initLightTrackings(chunk);
        chunk.lightTrackings[index] |= sectionMask << (LightUtils.getIndex(lightType) << 4);
    }

    public static void onChunkReceive(final Chunk chunk, final int sectionMask)
    {
        final int flagMask = sectionMask | (sectionMask << 16);

        if (chunk.neighborLightTrackings != null)
        {
            for (int i = 0; i < chunk.neighborLightTrackings.length; ++i)
                chunk.neighborLightTrackings[i] &= ~flagMask;
        }

        final IChunkProvider provider = chunk.getWorld().getChunkProvider();

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            int index = getHorizontalFlagIndex(dir);

            final EnumFacing oppDir = dir.getOpposite();

            final Chunk nChunk = provider.getLoadedChunk(chunk.x + dir.getFrontOffsetX(), chunk.z + dir.getFrontOffsetZ());

            if (chunk.lightTrackings != null)
            {
                for (int offset = -1; offset <= 1; ++offset)
                {
                    final int flags = chunk.lightTrackings[index + offset] & flagMask;
                    chunk.lightTrackings[index + offset] &= ~flagMask;

                    if (nChunk != null && flags != 0)
                        LightBoundaryCheckHooks.flagHorizontalSecBoundaryForCheckClient(nChunk, oppDir, offset, flags);
                }
            }

            if (nChunk == null || nChunk.lightTrackings == null)
                continue;

            index = getHorizontalFlagIndex(oppDir);

            for (int offset = -1; offset <= 1; ++offset)
            {
                final int flags = nChunk.lightTrackings[index + offset] & flagMask;

                if (flags != 0)
                    LightBoundaryCheckHooks.flagHorizontalSecBoundaryForCheckClient(chunk, dir, offset, flags);
            }
        }

        if (chunk.lightTrackings == null)
            return;

        int index = LightBoundaryCheckHooks.getVerticalFlagIndex(EnumFacing.DOWN, EnumBoundaryFacing.OUT);

        int flags = ((chunk.lightTrackings[index] & flagMask) >>> 1) & ~flagMask;
        chunk.lightTrackings[index] &= ~flagMask;

        if (flags != 0)
            LightBoundaryCheckHooks.flagVerticalSecBoundaryForCheckClient(chunk, EnumFacing.UP, flags);

        index = LightBoundaryCheckHooks.getVerticalFlagIndex(EnumFacing.UP, EnumBoundaryFacing.OUT);

        flags = ((chunk.lightTrackings[index] & flagMask) << 1) & ~flagMask;
        chunk.lightTrackings[index] &= ~flagMask;

        if (flags != 0)
            LightBoundaryCheckHooks.flagVerticalSecBoundaryForCheckClient(chunk, EnumFacing.DOWN, flags);
    }

    public static void sendLightTrackings(final PlayerChunkMapEntry chunk, final int sectionMask, final PlayerChunkMap chunkMap)
    {
        for (final EntityPlayerMP player : chunk.lightTrackings.keySet())
            sendLightTrackings(chunk, player, sectionMask, chunkMap);
    }

    public static void sendLightTrackings(
        final PlayerChunkMapEntry chunk,
        final EntityPlayerMP player,
        final PlayerChunkMap chunkMap
    )
    {
        sendLightTrackings(chunk, player, (1 << 16) - 1, chunkMap);
    }

    public static void sendLightTrackings(
        final PlayerChunkMapEntry chunk,
        final EntityPlayerMP player,
        final int sectionMask,
        final PlayerChunkMap chunkMap
    )
    {
        final int[] lightTrackings = extractSyncLightTrackings(chunk, player, sectionMask, chunkMap);

        if (lightTrackings != null)
            NewLightPacketHandler.INSTANCE.sendTo(new SPacketLightTracking(chunk.getPos(), lightTrackings), player);
    }

    private static @Nullable int[] extractSyncLightTrackings(
        final PlayerChunkMapEntry playerChunk,
        final EntityPlayerMP player,
        final int sectionMask,
        final PlayerChunkMap chunkMap
    )
    {
        final Chunk chunk = playerChunk.getChunk();

        if (chunk == null)
            return null;

        final int flagMask = sectionMask | (sectionMask << 16);

        applyTrackings(playerChunk, chunkMap);

        int[] ret = null;

        final int[] neighborLightTrackings = playerChunk.neighborLightTrackings.get(player);

        if (neighborLightTrackings != null)
        {
            for (int i = 0; i < NEIGHBOR_FLAG_COUNT; ++i)
                neighborLightTrackings[i] &= ~flagMask;
        }

        if (chunk.neighborLightChecks != null)
        {
            for (int i = 0; i < LightBoundaryCheckHooks.OUT_INDEX_OFFSET; ++i)
            {
                final int flags = chunk.neighborLightChecks[i] & flagMask;

                ret = addSyncLightTrackings(ret, flags, i);
            }
        }

        final int[] lightTrackings = playerChunk.lightTrackings.get(player);

        if (lightTrackings != null)
        {
            int index = NEIGHBOR_FLAG_COUNT;

            int flags = ((lightTrackings[index] & flagMask) >>> 1) & ~flagMask;
            lightTrackings[index] &= ~flagMask;

            ret = addSyncLightTrackings(ret, flags, LightBoundaryCheckHooks.getVerticalFlagIndex(EnumFacing.UP, EnumBoundaryFacing.IN));

            ++index;

            flags = ((lightTrackings[index] & flagMask) << 1) & ~flagMask;
            lightTrackings[index] &= ~flagMask;

            ret = addSyncLightTrackings(ret, flags, LightBoundaryCheckHooks.getVerticalFlagIndex(EnumFacing.DOWN, EnumBoundaryFacing.IN));
        }

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            applyTrackings(playerChunk, dir, chunkMap);

            int index = getHorizontalFlagIndex(dir);

            final EnumFacing oppDir = dir.getOpposite();

            final PlayerChunkMapEntry nPlayerChunk = chunkMap.getEntry(chunk.x + dir.getFrontOffsetX(), chunk.z + dir.getFrontOffsetZ());
            final boolean neighborLoaded = nPlayerChunk != null && nPlayerChunk.lightTrackings.containsKey(player);
            final int[] nLightTrackings = neighborLoaded ? nPlayerChunk.lightTrackings.get(player) : null;

            if (lightTrackings != null)
            {
                for (int offset = -1; offset <= 1; ++offset)
                {
                    if (neighborLoaded)
                    {
                        final int flags = lightTrackings[index + offset] & flagMask;

                        ret = addSyncLightTrackings(ret, flags, LightBoundaryCheckHooks.FLAG_COUNT_CLIENT + index + offset);
                    }

                    lightTrackings[index + offset] &= ~flagMask;
                }
            }

            if (nLightTrackings == null)
                continue;

            index = getHorizontalFlagIndex(oppDir);

            for (int offset = -1; offset <= 1; ++offset)
            {
                final int flags = nLightTrackings[index + offset] & flagMask;

                ret = addSyncLightTrackings(ret, flags, LightBoundaryCheckHooks.getHorizontalFlagIndex(dir, EnumBoundaryFacing.IN, offset));
            }
        }

        return ret;
    }

    public static void applySyncLightTrackins(final int[] lightTrackings, final int chunkX, final int chunkZ, final World world)
    {
        final IChunkProvider provider = world.getChunkProvider();

        final Chunk chunk = provider.getLoadedChunk(chunkX, chunkZ);

        if (chunk != null)
        {
            for (int i = 0; i < LightBoundaryCheckHooks.FLAG_COUNT_CLIENT; ++i)
            {
                final int flags = lightTrackings[i];

                if (flags != 0)
                    LightBoundaryCheckHooks.flagSecBoundaryForCheckClient(chunk, i, flags);
            }
        }

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final Chunk nChunk = provider.getLoadedChunk(chunkX + dir.getFrontOffsetX(), chunkZ + dir.getFrontOffsetZ());

            if (nChunk == null)
                continue;

            final int index = LightBoundaryCheckHooks.FLAG_COUNT_CLIENT + getHorizontalFlagIndex(dir);

            final EnumFacing oppDir = dir.getOpposite();

            for (int offset = -1; offset <= 1; ++offset)
            {
                final int flags = lightTrackings[index + offset];

                if (flags != 0)
                    LightBoundaryCheckHooks.flagHorizontalSecBoundaryForCheckClient(nChunk, oppDir, offset, flags);
            }
        }
    }

    private static @Nullable int[] addSyncLightTrackings(@Nullable int[] lightTrackings, final int flags, final int index)
    {
        if (flags != 0)
        {
            if (lightTrackings == null)
                lightTrackings = new int[SYNC_FLAG_COUNT];

            lightTrackings[index] |= flags;
        }

        return lightTrackings;
    }

    public static void addPlayer(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap)
    {
        applyTrackings(chunk, chunkMap);

        chunk.lightTrackings.put(player, null);

        final ChunkPos pos = chunk.getPos();

        int[] neighborLightTrackings = null;

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final PlayerChunkMapEntry nChunk = chunkMap.getEntry(pos.x + dir.getFrontOffsetX(), pos.z + dir.getFrontOffsetZ());

            if (nChunk == null || !nChunk.lightTrackings.containsKey(player))
            {
                if (neighborLightTrackings == null)
                    neighborLightTrackings = new int[PLAYER_NEIGHBOR_FLAG_COUNT];

                neighborLightTrackings[NEIGHBOR_FLAG_COUNT] |= (1 << dir.getOpposite().getHorizontalIndex());

                applyTrackings(chunk, dir, chunkMap);
            }
            else
            {
                final int[] lightTrackings = nChunk.neighborLightTrackings.get(player);

                if (lightTrackings != null)
                {
                    final int dirFlag = 1 << dir.getHorizontalIndex();

                    if (!isLightTrackingsEmpty(lightTrackings, dir))
                    {
                        copyLightTrackings(lightTrackings, initPlayerLightTrackings(chunk, player), dir);
                        clearLightTrackings(lightTrackings, dir);
                    }

                    lightTrackings[NEIGHBOR_FLAG_COUNT] &= ~dirFlag;

                    if (lightTrackings[NEIGHBOR_FLAG_COUNT] == 0)
                        nChunk.neighborLightTrackings.remove(player);
                }
            }
        }

        if (neighborLightTrackings != null)
            chunk.neighborLightTrackings.put(player, neighborLightTrackings);
    }

    public static void removePlayer(final EntityPlayerMP player, final PlayerChunkMapEntry chunk, final PlayerChunkMap chunkMap)
    {
        chunk.neighborLightTrackings.remove(player);

        final ChunkPos pos = chunk.getPos();

        final int[] lightTrackings = chunk.lightTrackings.get(player);

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final PlayerChunkMapEntry nChunk = chunkMap.getEntry(pos.x + dir.getFrontOffsetX(), pos.z + dir.getFrontOffsetZ());

            if (nChunk != null && nChunk.lightTrackings.containsKey(player))
            {
                int[] neighborLightTrackings = nChunk.neighborLightTrackings.get(player);

                if (neighborLightTrackings == null)
                {
                    neighborLightTrackings = new int[PLAYER_NEIGHBOR_FLAG_COUNT];
                    nChunk.neighborLightTrackings.put(player, neighborLightTrackings);
                }

                final int dirFlag = 1 << dir.getHorizontalIndex();

                neighborLightTrackings[NEIGHBOR_FLAG_COUNT] |= dirFlag;

                if (lightTrackings != null)
                    copyLightTrackings(lightTrackings, neighborLightTrackings, dir);
            }
        }

        chunk.lightTrackings.remove(player);
    }

    static void applyTrackings(final PlayerChunkMapEntry playerChunk, final PlayerChunkMap chunkMap)
    {
        final Chunk chunk = playerChunk.getChunk();

        if (chunk == null || chunk.lightTrackings == null || isLightTrackingsEmpty(chunk.lightTrackings))
            return;

        for (final Entry<EntityPlayerMP, int[]> item : playerChunk.lightTrackings.entrySet())
        {
            initPlayerLightTrackings(item);
            final int[] playerLightTrackings = item.getValue();

            for (int i = 0; i < chunk.lightTrackings.length; ++i)
                playerLightTrackings[i] |= chunk.lightTrackings[i];
        }

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            if (isLightTrackingsEmpty(chunk.lightTrackings, dir))
                continue;

            final PlayerChunkMapEntry nPlayerChunk = chunkMap.getEntry(chunk.x + dir.getFrontOffsetX(), chunk.z + dir.getFrontOffsetZ());

            if (nPlayerChunk == null)
                continue;

            for (final Entry<EntityPlayerMP, int[]> item : nPlayerChunk.neighborLightTrackings.entrySet())
            {
                final int[] playerLightTrackings = item.getValue();

                if ((playerLightTrackings[NEIGHBOR_FLAG_COUNT] & (1 << dir.getHorizontalIndex())) != 0)
                    copyLightTrackings(chunk.lightTrackings, playerLightTrackings, dir);
            }
        }

        Arrays.fill(chunk.lightTrackings, 0);
    }

    static void applyTrackings(final PlayerChunkMapEntry playerChunk, final EnumFacing dir, final PlayerChunkMap chunkMap)
    {
        final EnumFacing oppDir = dir.getOpposite();

        final ChunkPos pos = playerChunk.getPos();

        final Chunk chunk = playerChunk.getChunk();
        final Chunk nChunk = chunkMap.getWorldServer().getChunkProvider().getLoadedChunk(pos.x + dir.getFrontOffsetX(), pos.z + dir.getFrontOffsetZ());

        if (nChunk != null)
        {
            if (nChunk.lightTrackings != null && !isLightTrackingsEmpty(nChunk.lightTrackings, oppDir))
            {
                final PlayerChunkMapEntry nPlayerChunk = chunkMap.getEntry(pos.x + dir.getFrontOffsetX(), pos.z + dir.getFrontOffsetZ());

                if (nPlayerChunk != null)
                {
                    for (final Entry<EntityPlayerMP, int[]> item : nPlayerChunk.lightTrackings.entrySet())
                    {
                        initPlayerLightTrackings(item);
                        final int[] playerLightTrackings = item.getValue();

                        copyLightTrackings(nChunk.lightTrackings, playerLightTrackings, oppDir);
                    }
                }

                for (final Entry<EntityPlayerMP, int[]> item : playerChunk.neighborLightTrackings.entrySet())
                {
                    final int[] playerLightTrackings = item.getValue();

                    if ((playerLightTrackings[NEIGHBOR_FLAG_COUNT] & (1 << oppDir.getHorizontalIndex())) != 0)
                        copyLightTrackings(nChunk.lightTrackings, playerLightTrackings, oppDir);
                }

                clearLightTrackings(nChunk.lightTrackings, oppDir);
            }
        }
        else if (chunk != null && chunk.neighborLightTrackings != null && !isLightTrackingsEmpty(chunk.neighborLightTrackings, oppDir))
        {
            for (final Entry<EntityPlayerMP, int[]> item : playerChunk.neighborLightTrackings.entrySet())
            {
                final int[] playerLightTrackings = item.getValue();

                copyLightTrackings(chunk.neighborLightTrackings, playerLightTrackings, oppDir);
            }

            clearLightTrackings(chunk.neighborLightTrackings, oppDir);
        }
    }

    public static void onLoad(final World world, final Chunk chunk)
    {
        final IChunkProvider provider = world.getChunkProvider();

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final Chunk nChunk = provider.getLoadedChunk(chunk.x + dir.getFrontOffsetX(), chunk.z + dir.getFrontOffsetZ());

            if (nChunk != null && nChunk.neighborLightTrackings != null && !isLightTrackingsEmpty(nChunk.neighborLightTrackings, dir))
            {
                initLightTrackings(chunk);
                copyLightTrackings(nChunk.neighborLightTrackings, chunk.lightTrackings, dir);
                clearLightTrackings(nChunk.neighborLightTrackings, dir);
            }
        }
    }

    public static void onUnload(final World world, final Chunk chunk)
    {
        if (chunk.lightTrackings == null)
            return;

        final IChunkProvider provider = world.getChunkProvider();

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final Chunk nChunk = provider.getLoadedChunk(chunk.x + dir.getFrontOffsetX(), chunk.z + dir.getFrontOffsetZ());

            if (nChunk != null && !isLightTrackingsEmpty(chunk.lightTrackings, dir))
            {
                initNeighborLightTrackings(nChunk);
                copyLightTrackings(chunk.lightTrackings, nChunk.neighborLightTrackings, dir);
            }
        }
    }

    public static boolean isLightTrackingsEmpty(final int[] lightTrackings)
    {
        for (final int tracking : lightTrackings)
        {
            if (tracking != 0)
                return false;
        }

        return true;
    }

    public static boolean isLightTrackingsEmpty(final int[] lightTrackings, final EnumFacing dir)
    {
        final int index = getHorizontalFlagIndex(dir);

        for (int offset = -1; offset <= 1; ++offset)
        {
            if (lightTrackings[index + offset] != 0)
                return false;
        }

        return true;
    }

    public static void copyLightTrackings(final int[] src, final int[] dst, final EnumFacing dir)
    {
        final int index = getHorizontalFlagIndex(dir);

        for (int offset = -1; offset <= 1; ++offset)
            dst[index + offset] |= src[index + offset];
    }

    public static void clearLightTrackings(final int[] lightTrackings, final EnumFacing dir)
    {
        final int index = getHorizontalFlagIndex(dir);

        for (int offset = -1; offset <= 1; ++offset)
            lightTrackings[index + offset] = 0;
    }

    public static int[] initPlayerLightTrackings(final PlayerChunkMapEntry chunk, final EntityPlayerMP player)
    {
        int[] lightTrackings = chunk.lightTrackings.get(player);

        if (lightTrackings == null)
        {
            lightTrackings = new int[FLAG_COUNT];
            chunk.lightTrackings.put(player, lightTrackings);
        }

        return lightTrackings;
    }

    public static void initPlayerLightTrackings(final Entry<EntityPlayerMP, int[]> item)
    {
        if (item.getValue() == null)
            item.setValue(new int[FLAG_COUNT]);
    }

    public static void initLightTrackings(final Chunk chunk)
    {
        if (chunk.lightTrackings == null)
            chunk.lightTrackings = new int[FLAG_COUNT];
    }

    public static void initNeighborLightTrackings(final Chunk chunk)
    {
        if (chunk.neighborLightTrackings == null)
            chunk.neighborLightTrackings = new int[NEIGHBOR_FLAG_COUNT];
    }
}
