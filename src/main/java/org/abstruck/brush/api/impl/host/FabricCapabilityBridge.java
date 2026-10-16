package org.abstruck.brush.api.impl.host;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.abstruck.brush.api.BrushEnergyStorage;
import org.abstruck.brush.api.BrushEvents;
import org.abstruck.brush.api.BrushFluidStorage;
import org.abstruck.brush.api.impl.capability.CapabilityRegistry;
import org.abstruck.brush.api.impl.capability.HostCapabilityBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * S2（Fabric 宿主）能力桥：把 Brush 的流体储能接到 Fabric 的 **Transfer API** 上
 * （{@code FluidStorage.SIDED}，{@code Storage<FluidVariant>}），并能反方向把 Fabric 的存储包装成 Brush 接口
 * （2026-10-16）。
 *
 * <h2>Fabric 侧的事实（javap 实证）</h2>
 * <ul>
 *   <li>{@code FluidStorage.SIDED} 的类型是 {@code BlockApiLookup<Storage<FluidVariant>, Direction>} ——
 *       同样是**增量**语义（{@code insert/extract} 返回实际移动量）。</li>
 *   <li><b>Transfer API v1（本机 8.0.6）没有能量模块</b>：{@code net.fabricmc.fabric.api.transfer.v1.energy}
 *       不存在（javap: 找不到类）。因此 S2/S1 下的能量**只有 Brush 自己的注册表**这条通道 ——
 *       本桥不会为能量伪造一个不存在的宿主接口，{@code findEnergy} 直接返回 null。</li>
 *   <li>查询用 {@code BlockApiLookup#find(Level, BlockPos, Direction)}（默认方法）。</li>
 *   <li>Fabric 的"模拟"机制与 NeoForge 一样是事务：模拟时开一个事务、拿到结果后
 *       {@code abort()}。真实写入按**调用即刻生效**实现（我们不是宿主事务的参与者）。</li>
 * </ul>
 *
 * <p>本类只在 S2 下被装载（由 {@code BrushApiMod} 用 {@code Class.forName} 探测后经接口调用），
 * 所以 S1/S3 永远不会解析 Fabric 类型。</p>
 */
public final class FabricCapabilityBridge implements HostCapabilityBridge {

    private static final Logger LOGGER = LogManager.getLogger("BrushApi");

    private FabricCapabilityBridge() {
    }

    /** 由 {@code BrushApiMod} 在探测到 Fabric Loader 之后调用。 */
    public static void install() {
        registerAll();
        CapabilityRegistry.installHostBridge(new FabricCapabilityBridge());
        // Fabric 允许在**首次查询之前**随时注册。我们的条目可能晚于 init 才出现（内容注册相位在
        // BuiltInRegistries.bootStrap() 入口才触发），因此客户端就绪时再枚举一次，把后到的条目补登记。
        org.abstruck.brush.api.BrushEvents.CLIENT_READY.register(event -> registerAll());
    }

    /** 把注册表**当前**的条目登记进 Fabric（可重复调用：同方块重复注册会覆盖为最新提供者）。 */
    private static void registerAll() {
        int blockEntities = 0;
        int blocks = 0;

        for (CapabilityRegistry.FluidBlockEntityEntry entry : CapabilityRegistry.fluidBlockEntityEntries()) {
            FluidStorage.SIDED.registerForBlockEntities(
                    (blockEntity, side) -> asStorage(entry.provider().get(blockEntity, side)),
                    entry.type());
            blockEntities++;
        }
        for (CapabilityRegistry.FluidBlockEntry entry : CapabilityRegistry.fluidBlockEntries()) {
            FluidStorage.SIDED.registerForBlocks(
                    (level, pos, state, blockEntity, side) -> asStorage(entry.provider().get(level, pos, state, blockEntity, side)),
                    entry.block());
            blocks++;
        }

        LOGGER.info("[brush-api] S2 capability bridge: registered fluid(blockEntity=" + blockEntities
                + ", block=" + blocks + ") into Fabric FluidStorage.SIDED;"
                + " energy: Fabric transfer v1 has no energy module -- Brush registry only");
    }

    private static Storage<FluidVariant> asStorage(BrushFluidStorage storage) {
        return storage == null ? null : new BrushAsStorage(storage);
    }

    // ------------------------------------------------------------------ 宿主 → Brush

    @Override
    public BrushFluidStorage findFluid(Level level, BlockPos pos, Direction side) {
        Storage<FluidVariant> storage = FluidStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new StorageAsBrush(storage);
    }

    @Override
    public BrushEnergyStorage findEnergy(Level level, BlockPos pos, Direction side) {
        // Fabric 的 transfer v1 没有能量模块 —— 如实返回 null，不伪造。
        return null;
    }

    // ------------------------------------------------------------------ Brush → 宿主

    /** Brush 流体储罐 → Fabric {@code Storage<FluidVariant>}。 */
    static final class BrushAsStorage implements Storage<FluidVariant> {

        private final BrushFluidStorage storage;

        BrushAsStorage(BrushFluidStorage storage) {
            this.storage = storage;
        }

        @Override
        public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            if (resource == null || maxAmount <= 0) {
                return 0;
            }
            Fluid fluid = resource.getFluid();
            long moved = 0;
            for (int tank = 0; tank < storage.tankCount() && moved < maxAmount; tank++) {
                moved += Math.max(0L, storage.insert(tank, fluid, maxAmount - moved, false));
            }
            return Math.min(moved, maxAmount);
        }

        @Override
        public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            if (maxAmount <= 0) {
                return 0;
            }
            Fluid wanted = (resource == null) ? Fluids.EMPTY : resource.getFluid();
            long moved = 0;
            for (int tank = 0; tank < storage.tankCount() && moved < maxAmount; tank++) {
                moved += Math.max(0L, storage.extract(tank, wanted, maxAmount - moved, false));
            }
            return Math.min(moved, maxAmount);
        }

        @Override
        public Iterator<StorageView<FluidVariant>> iterator() {
            List<StorageView<FluidVariant>> views = new ArrayList<>();
            for (int tank = 0; tank < storage.tankCount(); tank++) {
                views.add(new TankView(storage, tank));
            }
            return views.iterator();
        }
    }

    /** 单个 Brush 储罐 → Fabric {@code StorageView<FluidVariant>}。 */
    static final class TankView implements StorageView<FluidVariant> {

        private final BrushFluidStorage storage;
        private final int tank;

        TankView(BrushFluidStorage storage, int tank) {
            this.storage = storage;
            this.tank = tank;
        }

        @Override
        public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            if (maxAmount <= 0) {
                return 0;
            }
            Fluid wanted = (resource == null) ? Fluids.EMPTY : resource.getFluid();
            return Math.max(0L, storage.extract(tank, wanted, maxAmount, false));
        }

        @Override
        public boolean isResourceBlank() {
            Fluid fluid = storage.fluidIn(tank);
            return fluid == null || fluid == Fluids.EMPTY || storage.amountIn(tank) <= 0;
        }

        @Override
        public FluidVariant getResource() {
            Fluid fluid = storage.fluidIn(tank);
            return (fluid == null || fluid == Fluids.EMPTY) ? FluidVariant.blank() : FluidVariant.of(fluid);
        }

        @Override
        public long getAmount() {
            return Math.max(0L, storage.amountIn(tank));
        }

        @Override
        public long getCapacity() {
            return Math.max(0L, storage.capacityOf(tank));
        }
    }

    // ------------------------------------------------------------------ 宿主存储的 Brush 视图

    /** Fabric 的 {@code Storage<FluidVariant>} → Brush 流体储罐视图（一个 view 当一个罐）。 */
    static final class StorageAsBrush implements BrushFluidStorage {

        private final Storage<FluidVariant> storage;

        StorageAsBrush(Storage<FluidVariant> storage) {
            this.storage = storage;
        }

        private List<StorageView<FluidVariant>> views() {
            List<StorageView<FluidVariant>> list = new ArrayList<>();
            for (StorageView<FluidVariant> view : storage) {
                list.add(view);
            }
            return list;
        }

        @Override
        public int tankCount() {
            return views().size();
        }

        @Override
        public Fluid fluidIn(int tank) {
            List<StorageView<FluidVariant>> list = views();
            if (tank < 0 || tank >= list.size()) {
                return Fluids.EMPTY;
            }
            FluidVariant variant = list.get(tank).getResource();
            Fluid fluid = (variant == null) ? null : variant.getFluid();
            return fluid == null ? Fluids.EMPTY : fluid;
        }

        @Override
        public long amountIn(int tank) {
            List<StorageView<FluidVariant>> list = views();
            return (tank < 0 || tank >= list.size()) ? 0L : Math.max(0L, list.get(tank).getAmount());
        }

        @Override
        public long capacityOf(int tank) {
            List<StorageView<FluidVariant>> list = views();
            return (tank < 0 || tank >= list.size()) ? 0L : Math.max(0L, list.get(tank).getCapacity());
        }

        @Override
        public long insert(int tank, Fluid fluid, long maxAmount, boolean simulate) {
            if (fluid == null || fluid == Fluids.EMPTY || maxAmount <= 0) {
                return 0;
            }
            return transfer(true, FluidVariant.of(fluid), maxAmount, simulate);
        }

        @Override
        public long extract(int tank, Fluid fluid, long maxAmount, boolean simulate) {
            if (maxAmount <= 0) {
                return 0;
            }
            FluidVariant variant = (fluid == null || fluid == Fluids.EMPTY)
                    ? FluidVariant.blank() : FluidVariant.of(fluid);
            return transfer(false, variant, maxAmount, simulate);
        }

        /**
         * 与宿主存储打交道：**始终由我们开一个外层事务** —— 真实写入 {@code commit()}、模拟 {@code abort()}。
         *
         * <p><b>为什么不再探测"当前事务"</b>：Fabric 的 {@code Transaction.getCurrentUnsafe()} 已被弃用，
         * 其 javadoc 原文是"强烈不建议使用"（只在实现 simulation 语义的兼容层、且外层事务是自己开的时才
         * 不得已用之），并警告"你以为提交了的东西可能被神不知鬼不觉地回滚"。
         * 我们宁可**明确失败**：检测到已有事务时抛可读异常，而不是把一个看不见的事务当成自己的。
         * 需要参与宿主事务的场景，请直接使用 Fabric 的 {@code Storage}。</p>
         */
        private long transfer(boolean insert, FluidVariant variant, long maxAmount, boolean simulate) {
            if (Transaction.isOpen()) {
                throw new IllegalStateException("Brush API: the Fabric reverse view must not be used inside an"
                        + " open transaction (Brush storages cannot see foreign transactions) -- call it outside"
                        + " the transaction, or use Fabric's Storage directly");
            }
            Transaction transaction = Transaction.openOuter();
            try {
                long moved = insert ? storage.insert(variant, maxAmount, transaction)
                        : storage.extract(variant, maxAmount, transaction);
                if (simulate) {
                    transaction.abort();
                } else {
                    transaction.commit();
                }
                return Math.max(0L, moved);
            } finally {
                transaction.close();
            }
        }
    }



}
