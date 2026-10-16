package org.abstruck.brush.api.impl.capability;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.abstruck.brush.api.BrushEnergyStorage;
import org.abstruck.brush.api.BrushFluidStorage;

/**
 * **宿主桥缝**：把 Brush 的储能注册表接到宿主（Fabric / NeoForge）的能力系统上，
 * 以及从宿主的能力系统里查询储能（2026-10-16）。
 *
 * <p>本接口**只用 Minecraft 类型**（不含任何宿主类型），因此 S1 下没有实现类也能编译、也能运行
 * （S1 = 纯 Brush，注册表自己就是全部答案）。具体实现放在 {@code impl.host} 下，
 * 由 {@code BrushApiMod} 在初始化期按宿主探测结果装载 —— 这样 S1/S2 下永远不会加载
 * NeoForge 的实现类，反之亦然。</p>
 */
public interface HostCapabilityBridge {

    /**
     * 查询宿主给出的流体储能。
     *
     * @return 宿主的储能包装成 Brush 接口；宿主不认识这个坐标时返回 {@code null}
     */
    BrushFluidStorage findFluid(Level level, BlockPos pos, Direction side);

    /**
     * 查询宿主给出的能量储能。
     *
     * @return 宿主的储能包装成 Brush 接口；宿主没有（或该平台没有标准能量 API）时返回 {@code null}
     */
    BrushEnergyStorage findEnergy(Level level, BlockPos pos, Direction side);
}
