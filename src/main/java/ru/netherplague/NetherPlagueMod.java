package ru.netherplague;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetherPlagueMod implements ModInitializer {
    public static final String MOD_ID = "netherplague";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int SCAN_RADIUS = 16;
    private static final int TICK_INTERVAL = 20;
    private static final int SPREAD_ATTEMPTS_PER_PORTAL = 6;
    private static final int MAX_SPREAD_RANGE = 10;

    private static final Map<String, LongSet> INFECTED_BLOCKS_BY_WORLD = new HashMap<>();

    @Override
    public void onInitialize() {
        ServerTickEvents.END_WORLD_TICK.register(this::tickWorld);
        PlayerBlockBreakEvents.BEFORE.register(this::beforeBlockBreak);
        PlayerBlockBreakEvents.AFTER.register(this::afterBlockBreak);
        LOGGER.info("Nether Plague loaded");
    }

    private void tickWorld(ServerWorld world) {
        if (world.getServer().getTicks() % TICK_INTERVAL != 0) {
            return;
        }

        List<BlockPos> portalBlocks = findPortalsNearPlayers(world);
        if (portalBlocks.isEmpty()) {
            return;
        }

        Random random = world.getRandom();
        for (BlockPos portalPos : portalBlocks) {
            for (int i = 0; i < SPREAD_ATTEMPTS_PER_PORTAL; i++) {
                trySpreadFromPortal(world, portalPos, random);
            }
        }
    }

    private List<BlockPos> findPortalsNearPlayers(ServerWorld world) {
        List<BlockPos> portals = new ArrayList<>();

        world.getPlayers().forEach(player -> {
            BlockPos center = player.getBlockPos();
            BlockPos.Mutable mutable = new BlockPos.Mutable();

            for (int x = center.getX() - SCAN_RADIUS; x <= center.getX() + SCAN_RADIUS; x++) {
                for (int y = center.getY() - SCAN_RADIUS; y <= center.getY() + SCAN_RADIUS; y++) {
                    for (int z = center.getZ() - SCAN_RADIUS; z <= center.getZ() + SCAN_RADIUS; z++) {
                        mutable.set(x, y, z);
                        if (world.getBlockState(mutable).isOf(Blocks.NETHER_PORTAL)) {
                            portals.add(mutable.toImmutable());
                        }
                    }
                }
            }
        });

        return portals;
    }

    private void trySpreadFromPortal(ServerWorld world, BlockPos portalPos, Random random) {
        int dx = random.nextBetween(-MAX_SPREAD_RANGE, MAX_SPREAD_RANGE);
        int dy = random.nextBetween(-MAX_SPREAD_RANGE / 2, MAX_SPREAD_RANGE / 2);
        int dz = random.nextBetween(-MAX_SPREAD_RANGE, MAX_SPREAD_RANGE);

        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }

        BlockPos target = portalPos.add(dx, dy, dz);
        infectAlongLine(world, portalPos, target);
    }

    private void infectAlongLine(ServerWorld world, BlockPos start, BlockPos end) {
        int dx = end.getX() - start.getX();
        int dy = end.getY() - start.getY();
        int dz = end.getZ() - start.getZ();

        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps == 0) {
            return;
        }

        for (int step = 1; step <= steps; step++) {
            int x = start.getX() + dx * step / steps;
            int y = start.getY() + dy * step / steps;
            int z = start.getZ() + dz * step / steps;
            BlockPos current = new BlockPos(x, y, z);

            BlockState state = world.getBlockState(current);

            if (state.isOf(Blocks.CRYING_OBSIDIAN)) {
                return;
            }

            if (state.isAir() || state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
                continue;
            }

            if (!isInfectable(world, current, state)) {
                return;
            }

            world.setBlockState(current, Blocks.NETHERRACK.getDefaultState(), Block.NOTIFY_ALL);
            markInfected(world, current);

            BlockPos above = current.up();
            if (world.getBlockState(above).isAir()) {
                world.setBlockState(above, Blocks.FIRE.getDefaultState(), Block.NOTIFY_ALL);
            }
            return;
        }
    }

    private boolean isInfectable(ServerWorld world, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity()) {
            return false;
        }

        Block block = state.getBlock();
        if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN || block == Blocks.BEDROCK || block == Blocks.RESPAWN_ANCHOR) {
            return false;
        }

        float hardness = state.getHardness(world, pos);
        if (hardness < 0) {
            return false;
        }

        return state.getFluidState().isEmpty();
    }

    private boolean beforeBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, net.minecraft.block.entity.BlockEntity blockEntity) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return true;
        }

        if (!isMarkedInfected(serverWorld, pos)) {
            return true;
        }

        if (!state.isOf(Blocks.NETHERRACK)) {
            unmarkInfected(serverWorld, pos);
            return true;
        }

        ItemStack tool = player.getMainHandStack();
        if (tool.isOf(Items.NETHERITE_PICKAXE)) {
            return true;
        }

        if (player instanceof ServerPlayerEntity serverPlayer) {
            serverPlayer.sendMessage(Text.literal("Заражённый блок можно сломать только незеритовой киркой."), true);
        }

        return false;
    }

    private void afterBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, net.minecraft.block.entity.BlockEntity blockEntity) {
        if (world instanceof ServerWorld serverWorld) {
            unmarkInfected(serverWorld, pos);
        }
    }

    private void markInfected(ServerWorld world, BlockPos pos) {
        infectedSetFor(world).add(pos.asLong());
    }

    private boolean isMarkedInfected(ServerWorld world, BlockPos pos) {
        return infectedSetFor(world).contains(pos.asLong());
    }

    private void unmarkInfected(ServerWorld world, BlockPos pos) {
        infectedSetFor(world).remove(pos.asLong());
    }

    private LongSet infectedSetFor(ServerWorld world) {
        String worldKey = world.getRegistryKey().getValue().toString();
        return INFECTED_BLOCKS_BY_WORLD.computeIfAbsent(worldKey, ignored -> new LongOpenHashSet());
    }
}
