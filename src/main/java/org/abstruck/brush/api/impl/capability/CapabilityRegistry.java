package org.abstruck.brush.api.impl.capability;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.abstruck.brush.api.BrushCapabilities;
import org.abstruck.brush.api.BrushEnergyStorage;
import org.abstruck.brush.api.BrushFluidStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 储能注册表（{@link BrushCapabilities} 的实现，2026-10-16）。
 *
 * <p>结构很朴素：四张表（流体方块 / 流体方块实体 / 能量方块 / 能量方块实体），
 * 查询时**先问宿主、再查自己的表**。用 {@link CopyOnWriteArrayList} 是因为
 * 注册通常发生在初始化期、查询发生在主线程 tick，两边的偶发交叠不该产生
 * {@code ConcurrentModificationException}。</p>
 *
 * <p><b>时序约定（重要）</b>：请在各 mod 的 {@code onInitialize()} 里注册。
 * Fabric 侧是查询期回查注册表，晚注册也能生效；**NeoForge 侧**能力注册事件只触发一次
 * （{@code RegisterCapabilitiesEvent}），事件之后新增的条目不会被转登记 ——
 * 这一点写在 {@link BrushCapabilities} 的文档里，不在运行时假装支持。</p>
 */
public final class CapabilityRegistry {

    private static final Logger LOGGER = LogManager.getLogger("BrushApi");

    /** 按方块登记的一条流体条目。 */
    public record FluidBlockEntry(Block block, BrushCapabilities.FluidBlockProvider provider) {
    }

    /** 按方块实体类型登记的一条流体条目。 */
    public record FluidBlockEntityEntry(BlockEntityType<?> type, BrushCapabilities.FluidBlockEntityProvider provider) {
    }

    /** 按方块登记的一条能量条目。 */
    public record EnergyBlockEntry(Block block, BrushCapabilities.EnergyBlockProvider provider) {
    }

    /** 按方块实体类型登记的一条能量条目。 */
    public record EnergyBlockEntityEntry(BlockEntityType<?> type, BrushCapabilities.EnergyBlockEntityProvider provider) {
    }

    private static final List<FluidBlockEntry> FLUID_BLOCKS = new CopyOnWriteArrayList<>();
    private static final List<FluidBlockEntityEntry> FLUID_BLOCK_ENTITIES = new CopyOnWriteArrayList<>();
    private static final List<EnergyBlockEntry> ENERGY_BLOCKS = new CopyOnWriteArrayList<>();
    private static final List<EnergyBlockEntityEntry> ENERGY_BLOCK_ENTITIES = new CopyOnWriteArrayList<>();

    /** 宿主桥；S1（无宿主）下保持 null。 */
    private static volatile HostCapabilityBridge hostBridge;

    private CapabilityRegistry() {
    }

    // ------------------------------------------------------------------ 登记

    public static void registerFluidBlock(Block block, BrushCapabilities.FluidBlockProvider provider) {
        requireNonNull(block, "block");
        requireNonNull(provider, "provider");
        FLUID_BLOCKS.add(new FluidBlockEntry(block, provider));
    }

    public static void registerFluidBlockEntity(BlockEntityType<?> type,
                                                BrushCapabilities.FluidBlockEntityProvider provider) {
        requireNonNull(type, "type");
        requireNonNull(provider, "provider");
        FLUID_BLOCK_ENTITIES.add(new FluidBlockEntityEntry(type, provider));
    }

    public static void registerEnergyBlock(Block block, BrushCapabilities.EnergyBlockProvider provider) {
        requireNonNull(block, "block");
        requireNonNull(provider, "provider");
        ENERGY_BLOCKS.add(new EnergyBlockEntry(block, provider));
    }

    public static void registerEnergyBlockEntity(BlockEntityType<?> type,
                                                 BrushCapabilities.EnergyBlockEntityProvider provider) {
        requireNonNull(type, "type");
        requireNonNull(provider, "provider");
        ENERGY_BLOCK_ENTITIES.add(new EnergyBlockEntityEntry(type, provider));
    }

    private static void requireNonNull(Object value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("BrushCapabilities." + what + " must not be null");
        }
    }

    // ------------------------------------------------------------------ 宿主桥

    /** 由宿主桥实现类在装载时调用（S1 不调用）。 */
    public static void installHostBridge(HostCapabilityBridge bridge) {
        hostBridge = bridge;
        LOGGER.info("[brush-api] host capability bridge installed: " + bridge.getClass().getName());
    }

    /** 给宿主桥读取的只读视图（宿主自己的注册入口要用）。 */
    public static List<FluidBlockEntry> fluidBlockEntries() {
        return List.copyOf(FLUID_BLOCKS);
    }

    public static List<FluidBlockEntityEntry> fluidBlockEntityEntries() {
        return List.copyOf(FLUID_BLOCK_ENTITIES);
    }

    public static List<EnergyBlockEntry> energyBlockEntries() {
        return List.copyOf(ENERGY_BLOCKS);
    }

    public static List<EnergyBlockEntityEntry> energyBlockEntityEntries() {
        return List.copyOf(ENERGY_BLOCK_ENTITIES);
    }

    // ------------------------------------------------------------------ 查询

    public static Optional<BrushFluidStorage> findFluid(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        HostCapabilityBridge bridge = hostBridge;
        if (bridge != null) {
            BrushFluidStorage fromHost = bridge.findFluid(level, pos, side);
            if (fromHost != null) {
                return Optional.of(fromHost);
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            for (FluidBlockEntityEntry entry : FLUID_BLOCK_ENTITIES) {
                if (!matches(entry.type(), blockEntity)) {
                    continue;
                }
                BrushFluidStorage storage = entry.provider().get(blockEntity, side);
                if (storage != null) {
                    return Optional.of(storage);
                }
            }
        }
        BlockState state = level.getBlockState(pos);
        for (FluidBlockEntry entry : FLUID_BLOCKS) {
            if (entry.block() != state.getBlock()) {
                continue;
            }
            BrushFluidStorage storage = entry.provider().get(level, pos, state, blockEntity, side);
            if (storage != null) {
                return Optional.of(storage);
            }
        }
        return Optional.empty();
    }

    public static Optional<BrushEnergyStorage> findEnergy(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        HostCapabilityBridge bridge = hostBridge;
        if (bridge != null) {
            BrushEnergyStorage fromHost = bridge.findEnergy(level, pos, side);
            if (fromHost != null) {
                return Optional.of(fromHost);
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            for (EnergyBlockEntityEntry entry : ENERGY_BLOCK_ENTITIES) {
                if (!matches(entry.type(), blockEntity)) {
                    continue;
                }
                BrushEnergyStorage storage = entry.provider().get(blockEntity, side);
                if (storage != null) {
                    return Optional.of(storage);
                }
            }
        }
        BlockState state = level.getBlockState(pos);
        for (EnergyBlockEntry entry : ENERGY_BLOCKS) {
            if (entry.block() != state.getBlock()) {
                continue;
            }
            BrushEnergyStorage storage = entry.provider().get(level, pos, state, blockEntity, side);
            if (storage != null) {
                return Optional.of(storage);
            }
        }
        return Optional.empty();
    }

    /**
     * 方块实体是否属于登记的该类型。
     *
     * <p>用 {@code getType()} 恒等比较：26.1.2 的 {@code BlockEntityType#isValid} 收的是
     * {@link BlockState}（不是方块实体），语义也不同（那是"这个类型能否放在这种方块状态上"）。
     * 2026-10-16 编译期实测修正。</p>
     */
    private static boolean matches(BlockEntityType<?> type, BlockEntity blockEntity) {
        return type == blockEntity.getType();
    }

    /** 自检 / 诊断：四张表的条目数。 */
    public static int[] registeredCounts() {
        return new int[] {
                FLUID_BLOCKS.size(),
                FLUID_BLOCK_ENTITIES.size(),
                ENERGY_BLOCKS.size(),
                ENERGY_BLOCK_ENTITIES.size()
        };
    }
}
