package org.abstruck.brush.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.abstruck.brush.api.impl.capability.CapabilityRegistry;

import java.util.Optional;
import java.util.function.Function;

/**
 * 流体 / 能量储能的**注册与查询**入口（Brush API，2026-10-16）。
 *
 * <h2>它做什么</h2>
 * <ol>
 *   <li><b>注册</b>：mod 作者把自己的方块 / 方块实体声明成 {@link BrushFluidStorage} 或
 *       {@link BrushEnergyStorage} 的提供者。注册是"声明一次、三个运行时都成立"的 ——
 *       平台差异由本 API 在宿主桥里处理。</li>
 *   <li><b>查询</b>：拿到某个坐标 + 某个面的储能。<b>与宿主互通</b>：
 *       若该方块由宿主的 mod 实现（NeoForge 的 {@code ResourceHandler<FluidResource>} /
 *       {@code EnergyHandler}，Fabric 的 {@code Storage<FluidVariant>}），也会被包装成 Brush 接口返回；
 *       反之，**我们注册的储能也会暴露给宿主的 mod**（管道 / 机器可以按宿主的习惯找到它）。</li>
 * </ol>
 *
 * <h2>平台事实（写清楚，不含糊）</h2>
 * <table border="1">
 *   <tr><th>运行时</th><th>流体</th><th>能量</th></tr>
 *   <tr><td>S3 NeoForge</td><td>双向：{@code Capabilities.Fluid.BLOCK}（增量 transfer API）</td>
 *       <td>双向：{@code Capabilities.Energy.BLOCK}（{@code EnergyHandler}）</td></tr>
 *   <tr><td>S2 Fabric</td><td>双向：{@code FluidStorage.SIDED}（{@code Storage<FluidVariant>}）</td>
 *       <td><b>只有 Brush 自己的注册表</b> —— Fabric transfer API v1 没有能量模块，我们不伪造一个宿主接口</td></tr>
 *   <tr><td>S1 纯 Brush</td><td>Brush 自己的注册表（宿主不存在）</td><td>同上</td></tr>
 * </table>
 *
 * <h2>已知简化</h2>
 * <ul>
 *   <li><b>不带数据组件的流体变体</b>：Brush 接口只用 {@code Fluid} 标识流体。
 *       宿主侧带组件（如药水）的变体在适配时按"同种流体"处理，组件信息会在往返中丢失 ——
 *       需要精确变体的场景请直接使用宿主 API。</li>
 *   <li><b>事务</b>：注入 / 抽取按调用即刻生效（宿主事务只影响宿主自己的 handler，
 *       不影响我们注册的储能）。</li>
 *   <li><b>没有为原版方块加 mixin</b>：要让原版方块也暴露储能，用下面的
 *       {@code register*Block(...)} 注册即可（宿主侧本来就有"按方块注册"的官方入口），
 *       不需要改动任何 vanilla 类。</li>
 *   <li><b>注册时机（硬约束，2026-10-16 实测）</b>：必须在**你的方块 / 方块实体类型已经存在之后**
 *       注册 —— 通常是内容注册阶段或之后。**不要在 mod 的 {@code onInitialize()} 里引用
 *       {@code Blocks} / {@code BlockEntityType} 这类注册表支撑的类**：那会把它们提前类初始化，
 *       而此时 MC 还没 bootstrap，会直接抛
 *       {@code IllegalArgumentException: Not bootstrapped (called from registry ...)}（S2/S3 实测崩溃）。
 *       同时，宿主侧的转登记只在宿主的能力事件时发生一次（NeoForge），或在装载桥时枚举一次（Fabric），
 *       所以**晚于该时点新增的条目不会再被转登记**（查询仍然走 Brush 自己的注册表）。</li>
 * </ul>
 */
public final class BrushCapabilities {

    private BrushCapabilities() {
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 按**方块**注册流体储能（适合无方块实体的方块，或按方块状态决定的情况）。
     *
     * @param provider {@code (level, pos, state, blockEntity, side) -> 储能或 null}；{@code side} 可为 null
     */
    public static void registerFluidBlock(Block block, FluidBlockProvider provider) {
        CapabilityRegistry.registerFluidBlock(block, provider);
    }

    /** 按**方块实体类型**注册流体储能（最常见的形式）。 */
    public static void registerFluidBlockEntity(BlockEntityType<?> type, FluidBlockEntityProvider provider) {
        CapabilityRegistry.registerFluidBlockEntity(type, provider);
    }

    /** 按**方块**注册能量储能。 */
    public static void registerEnergyBlock(Block block, EnergyBlockProvider provider) {
        CapabilityRegistry.registerEnergyBlock(block, provider);
    }

    /** 按**方块实体类型**注册能量储能。 */
    public static void registerEnergyBlockEntity(BlockEntityType<?> type, EnergyBlockEntityProvider provider) {
        CapabilityRegistry.registerEnergyBlockEntity(type, provider);
    }

    /**
     * 简单的"方块实体恒定提供同一个储能"的便捷注册（不区分面）。
     *
     * <p>转型是安全的：注册表只在 {@code blockEntity.getType() == type} 时才调用该提供者
     * （见 {@code CapabilityRegistry#matches}），因此 {@code be} 一定是 {@code T}。
     * 这里用 unchecked 转型只是因为泛型擦除 —— 不是掩盖失败。</p>
     */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> void registerFluidBlockEntity(
            BlockEntityType<T> type, Function<T, BrushFluidStorage> storage) {
        registerFluidBlockEntity(type, (be, side) -> storage.apply((T) be));
    }

    /** 便捷注册（能量版，说明同上）。 */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> void registerEnergyBlockEntity(
            BlockEntityType<T> type, Function<T, BrushEnergyStorage> storage) {
        registerEnergyBlockEntity(type, (be, side) -> storage.apply((T) be));
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 查询某坐标某面的流体储能。
     *
     * <p>顺序：先问宿主（S2/S3 下宿主的 mod 可能实现了它），再回落到 Brush 自己的注册表。
     * 两边都没有时返回 {@link Optional#empty()}。</p>
     */
    public static Optional<BrushFluidStorage> fluid(Level level, BlockPos pos, Direction side) {
        return CapabilityRegistry.findFluid(level, pos, side);
    }

    /** 查询某坐标某面的能量储能（顺序同 {@link #fluid}）。 */
    public static Optional<BrushEnergyStorage> energy(Level level, BlockPos pos, Direction side) {
        return CapabilityRegistry.findEnergy(level, pos, side);
    }

    /** 面向调试 / 自检：当前已注册的条目数（流体方块 / 流体方块实体 / 能量方块 / 能量方块实体）。 */
    public static int[] registeredCounts() {
        return CapabilityRegistry.registeredCounts();
    }

    // ------------------------------------------------------------------ 提供者

    /** 按方块提供流体储能。{@code side} 为 {@code null} 表示"不分面"。 */
    @FunctionalInterface
    public interface FluidBlockProvider {
        BrushFluidStorage get(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, Direction side);
    }

    /** 按方块实体提供流体储能。{@code side} 为 {@code null} 表示"不分面"。 */
    @FunctionalInterface
    public interface FluidBlockEntityProvider {
        BrushFluidStorage get(BlockEntity blockEntity, Direction side);
    }

    /** 按方块提供能量储能。{@code side} 为 {@code null} 表示"不分面"。 */
    @FunctionalInterface
    public interface EnergyBlockProvider {
        BrushEnergyStorage get(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, Direction side);
    }

    /** 按方块实体提供能量储能。{@code side} 为 {@code null} 表示"不分面"。 */
    @FunctionalInterface
    public interface EnergyBlockEntityProvider {
        BrushEnergyStorage get(BlockEntity blockEntity, Direction side);
    }
}
