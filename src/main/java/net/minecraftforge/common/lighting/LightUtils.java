package net.minecraftforge.common.lighting;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.AxisDirection;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

public class LightUtils
{
    public static final EnumSkyBlock[] ENUM_SKY_BLOCK_VALUES = EnumSkyBlock.values();
    public static final AxisDirection[] ENUM_AXIS_DIRECTION_VALUES = AxisDirection.values();

    private static final int[] lightTypeIndex = new int[2];
    private static final int[] BOUNDARY_REGIONS = new int[16];

    static
    {
        lightTypeIndex[EnumSkyBlock.BLOCK.ordinal()] = 0;
        lightTypeIndex[EnumSkyBlock.SKY.ordinal()] = 1;

        BOUNDARY_REGIONS[0] = -1;
        BOUNDARY_REGIONS[15] = 1;
    }

    public static int getIndex(final EnumSkyBlock lightType)
    {
        return lightTypeIndex[lightType.ordinal()];
    }

    static int getBoundaryRegion(final int coord)
    {
        return BOUNDARY_REGIONS[coord];
    }

    static int getBoundaryRegionSigned(final int region, final int xOffset, final int zOffset)
    {
        final int sgn = (xOffset - zOffset) >> 1;

        return (region ^ sgn) - sgn;
    }

    static int getBoundaryRegion(final int x, final int z, final EnumFacing dir)
    {
        final int xOffset = dir.getFrontOffsetX();
        final int zOffset = dir.getFrontOffsetZ();

        return getBoundaryRegionSigned(getBoundaryRegion((x & 15) & (-(zOffset & 1)) | (z & 15) & (-(xOffset & 1))), xOffset, zOffset);
    }

    public static void scheduleRelightChecksForArea(final World world, final EnumSkyBlock lightType, final int xMin, final int yMin, final int zMin, final int xMax, final int yMax, final int zMax, final MutableBlockPos pos)
    {
        for (int x = xMin; x <= xMax; ++x)
        {
            for (int z = zMin; z <= zMax; ++z)
            {
                scheduleRelightChecksForColumn(world, lightType, x, z, yMin, yMax, pos);
            }
        }
    }

    public static void scheduleRelightChecksForColumn(final World world, final EnumSkyBlock lightType, final int x, final int z, final int yMin, final int yMax, final MutableBlockPos pos)
    {
        for (int y = yMin; y <= yMax; ++y)
        {
            world.checkLightFor(lightType, pos.setPos(x, y, z));
        }
    }
}
