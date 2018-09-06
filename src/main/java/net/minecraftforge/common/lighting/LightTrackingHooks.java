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

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.lighting.LightBoundaryCheckHooks.EnumBoundaryFacing;

public class LightTrackingHooks
{
    private static final int NEIGHBOR_FLAG_COUNT = 12;
    private static final int FLAG_COUNT = NEIGHBOR_FLAG_COUNT + 2;

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

    public static void onLoad(final World world, final Chunk chunk)
    {
        final IChunkProvider provider = world.getChunkProvider();

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final Chunk nChunk = provider.getLoadedChunk(chunk.x + dir.getFrontOffsetX(), chunk.z + dir.getFrontOffsetZ());

            if (nChunk != null && nChunk.neighborLightChecks != null)
            {
                final int index = getHorizontalFlagIndex(dir);

                for (int offset = -1; offset <= 1; ++offset)
                {
                    final int flags = nChunk.neighborLightTrackings[index + offset];

                    if (flags != 0)
                    {
                        initLightTrackings(chunk);
                        chunk.lightTrackings[index + offset] = nChunk.neighborLightTrackings[index + offset];
                        nChunk.neighborLightTrackings[index + offset] = 0;
                    }
                }
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

            if (nChunk != null)
            {
                final int index = getHorizontalFlagIndex(dir);

                for (int offset = -1; offset <= 1; ++offset)
                {
                    final int flags = chunk.lightTrackings[index + offset];

                    if (flags != 0)
                    {
                        initNeighborLightTrackings(nChunk);
                        nChunk.neighborLightTrackings[index + offset] = chunk.lightTrackings[index + offset];
                    }
                }
            }
        }
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
