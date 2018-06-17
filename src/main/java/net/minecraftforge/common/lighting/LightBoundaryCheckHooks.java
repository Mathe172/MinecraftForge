package net.minecraftforge.common.lighting;

import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.AxisDirection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.util.math.BlockPos.PooledMutableBlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.FMLLog;

public class LightBoundaryCheckHooks
{
    public static final String neighborLightChecksKey = "NeighborLightChecks";
    private static final int OUT_INDEX_OFFSET = 8;
    private static final int FLAG_COUNT = OUT_INDEX_OFFSET + 12;

    public static void flagInnerSecBoundaryForUpdate(final Chunk chunk, final BlockPos pos, final EnumSkyBlock lightType)
    {
        flagInnerChunkBoundaryForUpdate(chunk, pos.getX(), pos.getZ(), 1 << (pos.getY() >> 4), lightType);
    }

    private static int getBoundaryRegion(final int coord)
    {
        return (((coord + 1) >> 1) - 4) / 4;
    }

    private static void flagChunkBoundaryForUpdate(final Chunk chunk, final int index, final int sectionMask, final EnumSkyBlock lightType)
    {
        initNeighborLightChecks(chunk);
        chunk.neighborLightChecks[index] |= sectionMask << (LightUtils.getIndex(lightType) << 4);
        chunk.markDirty();
    }

    public static void flagInnerChunkBoundaryForUpdate(final Chunk chunk, final int x, final int z, final int sectionMask, final EnumSkyBlock lightType)
    {
        final int xRegion = getBoundaryRegion(x & 15);
        final int zRegion = getBoundaryRegion(z & 15);

        final int index = (xRegion * (zRegion - 2) + 2 * ((xRegion & 1) - 1) * (zRegion - 1) + 1) & 7;

        flagChunkBoundaryForUpdate(chunk, index, sectionMask, lightType);
    }

    public static int getFlagIndex(final EnumFacing dir, final EnumBoundaryFacing boundaryFacing)
    {
        return dir.getHorizontalIndex() * boundaryFacing.indexMultiplier + boundaryFacing.offset + 1;
    }

    public static void flagOuterSecBoundaryForUpdate(final Chunk chunk, final BlockPos pos, final EnumFacing dir, final EnumSkyBlock lightType)
    {
        flagOuterChunkBoundaryForUpdate(chunk, pos.getX(), pos.getZ(), dir, 1 << (pos.getY() >> 4), lightType);
    }

    public static void flagOuterChunkBoundaryForUpdate(final Chunk chunk, final int x, final int z, final EnumFacing dir, final int sectionMask, final EnumSkyBlock lightType)
    {
        final int xOffset = dir.getFrontOffsetX();
        final int zOffset = dir.getFrontOffsetZ();

        final int region = getBoundaryRegion((x & 15) * (zOffset & 1) + (z & 15) * (xOffset & 1)) * (xOffset - zOffset);

        final int index = getFlagIndex(dir, EnumBoundaryFacing.OUT) + region;

        flagChunkBoundaryForUpdate(chunk, index, sectionMask, lightType);
    }

    private static void mergeFlags(final Chunk chunk, final Chunk neighborChunk, final EnumFacing dir)
    {
        if (neighborChunk.neighborLightChecks == null)
            return;

        final int inIndex = getFlagIndex(dir, EnumBoundaryFacing.IN);
        final int outIndex = getFlagIndex(dir.getOpposite(), EnumBoundaryFacing.OUT);

        for (int offset = -1; offset <= 1; ++offset)
        {
            final int neighborFlags = neighborChunk.neighborLightChecks[outIndex + offset];

            if (neighborFlags != 0)
            {
                initNeighborLightChecks(chunk);
                chunk.neighborLightChecks[(inIndex - offset) & 7] |= neighborFlags;
                neighborChunk.neighborLightChecks[outIndex + offset] = 0;
            }
        }

        chunk.markDirty();
        neighborChunk.markDirty();
    }

    public static void scheduleRelightChecksForChunkBoundaries(final World world, final Chunk chunk)
    {
        final PooledMutableBlockPos pos = PooledMutableBlockPos.retain();

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final int xOffset = dir.getFrontOffsetX();
            final int zOffset = dir.getFrontOffsetZ();

            final Chunk nChunk = world.getChunkProvider().getLoadedChunk(chunk.x + xOffset, chunk.z + zOffset);

            if (nChunk == null)
                continue;

            // Merge flags upon loading of a chunk. This ensures that all flags are always already on the IN boundary below
            mergeFlags(chunk, nChunk, dir);
            mergeFlags(nChunk, chunk, dir.getOpposite());

            scheduleRelightChecksForNeighbor(world, nChunk, dir, pos);
            scheduleRelightChecksForInteriorBoundary(world, chunk, dir, false, pos);
        }

        for (final AxisDirection xAxis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
        {
            for (final AxisDirection zAxis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
            {
                final int xOffset = xAxis.getOffset();
                final int zOffset = zAxis.getOffset();

                if (world.getChunkProvider().getLoadedChunk(chunk.x + xOffset, chunk.z) != null && world.getChunkProvider().getLoadedChunk(chunk.x, chunk.z + zOffset) != null)
                    scheduleRelightChecksForCorner(world, chunk, xOffset, zOffset, null, pos);
            }
        }

        pos.release();
    }

    private static void scheduleRelightChecksForNeighbor(final World world, final Chunk nChunk, final EnumFacing dir, final MutableBlockPos pos)
    {
        scheduleRelightChecksForInteriorBoundary(world, nChunk, dir.getOpposite(), true, pos);

        final int xOffset = dir.getFrontOffsetX();
        final int zOffset = dir.getFrontOffsetZ();

        for (final AxisDirection axis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
        {
            final int xOffsetNeighbor = axis.getOffset() * (zOffset & 1);
            final int zOffsetNeighbor = axis.getOffset() * (xOffset & 1);

            if (world.getChunkProvider().getLoadedChunk(nChunk.x + xOffsetNeighbor, nChunk.z + zOffsetNeighbor) != null)
                scheduleRelightChecksForCorner(world, nChunk, -xOffset + xOffsetNeighbor, -zOffset + zOffsetNeighbor, dir, pos);
        }
    }

    private static void scheduleRelightChecksForCorner(
        final World world,
        final Chunk chunk,
        final int xOffset,
        final int zOffset,
        final @Nullable EnumFacing trackingDir,
        final MutableBlockPos pos
    )
    {
        if (chunk.neighborLightChecks == null)
            return;

        final int flagIndex = (xOffset * (zOffset - 2)) & 7;

        final int flags = chunk.neighborLightChecks[flagIndex];

        if (flags == 0)
            return;

        chunk.neighborLightChecks[flagIndex] = 0;

        final int x = (chunk.x << 4) + (((-xOffset) >> 1) & 15);
        final int z = (chunk.z << 4) + (((-zOffset) >> 1) & 15);

        for (final EnumSkyBlock lightType : LightUtils.ENUM_SKY_BLOCK_VALUES)
        {
            final int shift = LightUtils.getIndex(lightType) << 4;
            final int sectionMask = (flags >> shift) & ((1 << 16) - 1);

            for (int y = 0; y < 16; ++y)
            {
                if ((sectionMask & (1 << y)) != 0)
                    LightUtils.scheduleRelightChecksForColumn(world, lightType, x, z, y << 4, (y << 4) + 15, pos);
            }

            if (trackingDir != null && world instanceof WorldServer)
            {
                final PlayerChunkMap playerChunkMap = ((WorldServer) world).getPlayerChunkMap();
                final PlayerChunkMapEntry playerChunk = playerChunkMap.getEntry(chunk.x, chunk.z);

                if (playerChunk != null)
                    LightTrackingHooks.trackLightUpdates(playerChunk, playerChunkMap, sectionMask, lightType, trackingDir);
            }
        }
    }

    private static void scheduleRelightChecksForInteriorBoundary(
        final World world,
        final Chunk chunk,
        final EnumFacing dir,
        final boolean trackLighting,
        final MutableBlockPos pos
    )
    {
        if (chunk.neighborLightChecks == null)
            return;

        final int flagIndex = getFlagIndex(dir, EnumBoundaryFacing.IN); // OUT checks from neighbor are already merged

        final int flags = chunk.neighborLightChecks[flagIndex];

        if (flags == 0)
            return;

        chunk.neighborLightChecks[flagIndex] = 0;

        final int xOffset = dir.getFrontOffsetX();
        final int zOffset = dir.getFrontOffsetZ();

        // Get the area to check
        // Start in the corner...
        int xMin = chunk.x << 4;
        int zMin = chunk.z << 4;

        //move to other side of chunk if the direction is positive
        if ((xOffset | zOffset) > 0)
        {
            xMin += 15 * xOffset;
            zMin += 15 * zOffset;
        }

        // Shift perpendicular to dir
        final int xShift = zOffset & 1;
        final int zShift = xOffset & 1;

        xMin += xShift;
        zMin += zShift;

        final int xMax = xMin + 13 * xShift;
        final int zMax = zMin + 13 * zShift;

        for (final EnumSkyBlock lightType : LightUtils.ENUM_SKY_BLOCK_VALUES)
        {
            final int shift = LightUtils.getIndex(lightType) << 4;
            final int sectionMask = (flags >> shift) & ((1 << 16) - 1);

            for (int y = 0; y < 16; ++y)
            {
                if ((sectionMask & (1 << y)) != 0)
                    LightUtils.scheduleRelightChecksForArea(world, lightType, xMin, y << 4, zMin, xMax, (y << 4) + 15, zMax, pos);
            }

            if (trackLighting && world instanceof WorldServer)
            {
                final PlayerChunkMap playerChunkMap = ((WorldServer) world).getPlayerChunkMap();
                final PlayerChunkMapEntry playerChunk = playerChunkMap.getEntry(chunk.x, chunk.z);

                if (playerChunk != null)
                    LightTrackingHooks.trackLightUpdates(playerChunk, playerChunkMap, sectionMask, lightType, dir.getOpposite());
            }
        }
    }

    public static void initNeighborLightChecks(final Chunk chunk)
    {
        if (chunk.neighborLightChecks == null)
            chunk.neighborLightChecks = new int[FLAG_COUNT];
    }

    static void writeNeighborLightChecksToNBT(final Chunk chunk, final NBTTagCompound nbt)
    {
        if (chunk.neighborLightChecks == null)
            return;

        boolean empty = true;
        final NBTTagList list = new NBTTagList();

        for (final int flags : chunk.neighborLightChecks)
        {
            list.appendTag(new NBTTagInt(flags));

            if (flags != 0)
                empty = false;
        }

        if (empty)
            chunk.neighborLightChecks = null;
        else
            nbt.setTag(neighborLightChecksKey, list);
    }

    static void readNeighborLightChecksFromNBT(final Chunk chunk, final NBTTagCompound nbt)
    {
        if (nbt.hasKey(neighborLightChecksKey, 9))
        {
            final NBTTagList list = nbt.getTagList(neighborLightChecksKey, 3);

            if (list.tagCount() == FLAG_COUNT)
            {
                initNeighborLightChecks(chunk);

                for (int i = 0; i < FLAG_COUNT; ++i)
                    chunk.neighborLightChecks[i] = ((NBTTagInt) list.get(i)).getInt();
            }
            else
                FMLLog.info("Boundary checks for chunk (%s, %s) are discarded. They are probably from an older version.", chunk.x, chunk.z);
        }
    }

    private enum EnumBoundaryFacing
    {
        IN(2, 0),
        OUT(3, OUT_INDEX_OFFSET);

        final int indexMultiplier;
        final int offset;

        EnumBoundaryFacing(final int indexMultiplier, final int offset)
        {
            this.indexMultiplier = indexMultiplier;
            this.offset = offset;
        }
    }
}
