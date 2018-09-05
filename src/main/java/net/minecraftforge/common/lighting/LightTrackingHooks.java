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
import net.minecraft.world.chunk.Chunk;

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

    public static void initLightTrackings(final Chunk chunk)
    {
        if (chunk.lightTrackings == null)
            chunk.lightTrackings = new int[FLAG_COUNT];
    }
}
