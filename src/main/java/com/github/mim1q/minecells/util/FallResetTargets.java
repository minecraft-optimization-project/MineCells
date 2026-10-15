package com.github.mim1q.minecells.util;

import com.github.mim1q.minecells.dimension.MineCellsDimension;
import com.github.mim1q.minecells.structure.grid.GridBasedStructureUtils;
import com.github.mim1q.minecells.structure.grid.SpecialPointIds;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Finds where fall protection should put an entity without ever waiting for a chunk on the server thread.
 * <p>
 * Checkpoints can be up to ~1024 blocks away. Querying them through {@code World#getTopY}/{@code getBlockState}
 * blocks the server thread until their chunks are loaded or generated, which froze servers for several seconds.
 * (Even {@code World#isChunkLoaded} is not enough: it is true once a chunk's ticket level reaches FULL, while
 * it may still be loading.) Instead, only chunks that are already fully loaded are queried, and the nearest
 * unloaded candidate is loaded in the background.
 */
public final class FallResetTargets {
  private static final ChunkTicketType<ChunkPos> TICKET =
    ChunkTicketType.create("minecells_fall_reset", Comparator.comparingLong(ChunkPos::toLong), 100);

  private FallResetTargets() {}

  /**
   * @return the position to reset the entity to, or {@code null} if the target's chunk is still loading
   * (a load has been requested; call again on a later tick)
   */
  public static @Nullable BlockPos find(Entity entity, ServerWorld world) {
    var dimension = MineCellsDimension.of(world);
    var cell = MathUtils.getClosestMultiplePosition(entity.getBlockPos(), 1024);

    List<BlockPos> candidates = GridBasedStructureUtils.getSpecialPoints(world, cell, dimension.baseGenerator).stream()
      .filter(it -> it.id().equals(SpecialPointIds.CHECKPOINT))
      .map(it -> new BlockPos(it.offset().add(cell)))
      .filter(it -> it.getZ() < entity.getZ())
      .sorted(Comparator.comparingDouble(it -> MathHelper.square(it.getX() - entity.getX()) + MathHelper.square(it.getZ() - entity.getZ())))
      .toList();

    // The nearest valid checkpoint among the ones that are already loaded
    for (var candidate : candidates) {
      var chunk = getLoadedChunk(world, candidate);
      if (chunk == null) continue;
      var top = candidate.withY(chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, candidate.getX() & 15, candidate.getZ() & 15) + 1);
      var floor = top.down();
      if (!world.isOutOfHeightLimit(floor) && chunk.getBlockState(floor).isSideSolidFullSquare(chunk, floor, Direction.UP)) {
        return top;
      }
    }

    // Otherwise load the nearest unloaded one in the background and check it again once it's there
    for (var candidate : candidates) {
      if (getLoadedChunk(world, candidate) == null) {
        requestLoad(world, candidate);
        return null;
      }
    }

    var entrance = BlockPos.ofFloored(
      dimension.getNonOverworldTeleportPosition(entity.getBlockPos(), world, SpecialPointIds.ENTRANCE).getLeft()
    );
    if (getLoadedChunk(world, entrance) == null) {
      requestLoad(world, entrance);
      return null;
    }
    return entrance;
  }

  private static @Nullable WorldChunk getLoadedChunk(ServerWorld world, BlockPos pos) {
    return world.getChunkManager().getWorldChunk(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()));
  }

  private static void requestLoad(ServerWorld world, BlockPos pos) {
    var chunkPos = new ChunkPos(pos);
    world.getChunkManager().addTicket(TICKET, chunkPos, 1, chunkPos);
  }
}
