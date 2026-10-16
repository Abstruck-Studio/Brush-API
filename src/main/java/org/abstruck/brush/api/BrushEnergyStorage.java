package org.abstruck.brush.api;

/**
 * 能量（FE 等价的通用能量单位）储能的**增量**接口（Brush API，2026-10-16）。
 *
 * <p>语义与 {@link BrushFluidStorage} 一致：按 {@code (最大量, simulate)} 调用，
 * 返回**实际**接收 / 抽取的量。量用 {@code long}，因为宿主之间宽度不同
 * （NeoForge 的 {@code EnergyHandler}/{@code IEnergyStorage} 是 {@code int}，
 * Fabric 生态历史上是 {@code long}）；往返适配时按宿主宽度**饱和截断**。</p>
 *
 * <p><b>三运行时一致</b>：只用基本类型，S1/S2/S3 同一份字节码。
 * 注意 **Fabric 的 transfer API 没有能量模块**（v1 只有流体）—— 因此在 S2 下，
 * 能量储能只能通过本 API 查询（见 {@link BrushCapabilities}），我们不会假装它有一个不存在的宿主接口。</p>
 *
 * @see BrushFluidStorage
 * @see BrushCapabilities
 */
public interface BrushEnergyStorage {

    /** 当前储能。 */
    long stored();

    /** 容量上限。 */
    long capacity();

    /**
     * 接收能量（增量）。
     *
     * @param maxAmount 最多接收多少（&ge; 0）
     * @param simulate  {@code true} = 只试算不改状态
     * @return **实际**接收量（&ge; 0，且 &le; maxAmount）
     */
    long receive(long maxAmount, boolean simulate);

    /**
     * 抽取能量（增量）。
     *
     * @param maxAmount 最多抽取多少（&ge; 0）
     * @param simulate  {@code true} = 只试算不改状态
     * @return **实际**抽取量（&ge; 0，且 &le; maxAmount）
     */
    long extract(long maxAmount, boolean simulate);

    /** 是否允许接收（默认允许）。 */
    default boolean canReceive() {
        return true;
    }

    /** 是否允许抽取（默认允许）。 */
    default boolean canExtract() {
        return true;
    }
}
