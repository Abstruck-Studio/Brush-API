package org.abstruck.brush.api;

import net.minecraft.world.level.material.Fluid;

/**
 * 流体储罐的**增量**接口（Brush API，2026-10-16）。
 *
 * <p><b>为什么是"增量"</b>：所有会改变量的操作都按 {@code (流体, 最大移动量, simulate)} 的形式调用，
 * 并**返回实际移动的量**（而不是"成功/失败"或"全有全无"）。这与两个宿主的现代 API 语义一致：
 * NeoForge 的 {@code ResourceHandler<FluidResource>}（{@code insert/extract} 返回实际量）与
 * Fabric 的 {@code Storage<FluidVariant>}（同上）。</p>
 *
 * <p><b>三运行时一致</b>：本接口只用 Minecraft 自带类型（{@link Fluid}）与基本类型，
 * 因此 S1（纯 Brush）/ S2（Fabric）/ S3（NeoForge）下**同一份字节码**都成立。
 * 平台特有的"带数据组件的流体变体"暂不建模（见 {@link BrushCapabilities} 的说明）。</p>
 *
 * <p><b>线程</b>：与宿主一致 —— 只应在服务端逻辑线程（tick / 方块实体逻辑）里调用。</p>
 *
 * @see BrushEnergyStorage
 * @see BrushCapabilities
 */
public interface BrushFluidStorage {

    /** 储罐数量（&ge; 0）。 */
    int tankCount();

    /**
     * 第 {@code tank} 个储罐当前的流体。
     *
     * @param tank 储罐下标，{@code 0 <= tank < tankCount()}
     * @return 当前流体；空罐返回 {@code net.minecraft.world.level.material.Fluids.EMPTY}
     */
    Fluid fluidIn(int tank);

    /** 第 {@code tank} 个储罐当前的流体量（单位：mB 等价的"滴"，与宿主一致）。 */
    long amountIn(int tank);

    /** 第 {@code tank} 个储罐的容量。 */
    long capacityOf(int tank);

    /**
     * 向第 {@code tank} 个储罐注入流体（增量）。
     *
     * @param tank      储罐下标
     * @param fluid     要注入的流体；{@code Fluids.EMPTY} 视为"无事发生"，返回 0
     * @param maxAmount 最多注入多少（&ge; 0）
     * @param simulate  {@code true} = 只试算不改状态
     * @return **实际**注入量（&ge; 0，且 &le; maxAmount）
     */
    long insert(int tank, Fluid fluid, long maxAmount, boolean simulate);

    /**
     * 从第 {@code tank} 个储罐抽取流体（增量）。
     *
     * @param tank      储罐下标
     * @param fluid     要抽取的流体；{@code Fluids.EMPTY} 视为"任意流体"（与宿主 drain 语义一致）
     * @param maxAmount 最多抽取多少（&ge; 0）
     * @param simulate  {@code true} = 只试算不改状态
     * @return **实际**抽取量（&ge; 0，且 &le; maxAmount）
     */
    long extract(int tank, Fluid fluid, long maxAmount, boolean simulate);

    /**
     * 该储罐是否接受这种流体（默认全部接受）。
     *
     * <p>宿主适配器会把它映射到各自的合法性检查（NeoForge {@code ResourceHandler#isValid}；
     * Fabric 侧没有对应概念，只在注入时用于提前拒绝）。</p>
     */
    default boolean isValid(int tank, Fluid fluid) {
        return true;
    }
}
