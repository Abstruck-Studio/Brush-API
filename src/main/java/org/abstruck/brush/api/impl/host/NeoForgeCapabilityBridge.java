package org.abstruck.brush.api.impl.host;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.abstruck.brush.api.BrushEnergyStorage;
import org.abstruck.brush.api.BrushFluidStorage;
import org.abstruck.brush.api.impl.capability.CapabilityRegistry;
import org.abstruck.brush.api.impl.capability.HostCapabilityBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * S3（NeoForge 宿主）能力桥：把 Brush 的流体 / 能量储能接到 NeoForge 的**增量 transfer API** 上，
 * 并能在反方向把 NeoForge 的 handler 包装成 Brush 接口（2026-10-16）。
 *
 * <h2>NeoForge 26.1.2 的事实（javap 实证，不是推测）</h2>
 * <ul>
 *   <li>{@code Capabilities.Fluid.BLOCK} 的类型是
 *       {@code BlockCapability<ResourceHandler<FluidResource>, Direction>} —— 即**增量**语义
 *       （{@code insert/extract} 返回实际移动量），旧的 {@code IFluidHandler} 仍在，但只是 {@code of(...)} 包装。</li>
 *   <li>{@code Capabilities.Energy.BLOCK} 是 {@code BlockCapability<EnergyHandler, Direction>}。</li>
 *   <li>查询用 {@code BlockCapability#getCapability(Level, BlockPos, BlockState, BlockEntity, Direction)}
 *       —— 刻意**不用** {@code level.getCapability(...)}：被 patch 过的 {@code Level} 与本项目
 *       编译期 classpath 上的 vanilla jar 同名冲突，用 {@code BlockCapability} 自带入口可绕开。</li>
 *   <li>本版本 {@code TransactionContext} 只有一个 {@code depth()} 方法：没有快照 / 回滚回调可挂。
 *       因此我们的适配器按**调用即刻生效**实现（需要模拟时用"开事务不提交"的方式）。</li>
 * </ul>
 *
 * <p>本类只在 S3 下被装载（由 {@code BrushApiMod} 通过 {@code HostSeams.onModEventBus} 触发），
 * 所以 S1/S2 永远不会解析 NeoForge 类型。</p>
 */
public final class NeoForgeCapabilityBridge implements HostCapabilityBridge {

    private static final Logger LOGGER = LogManager.getLogger("BrushApi");

    private NeoForgeCapabilityBridge() {
    }

    /** 由 {@code BrushApiMod} 在拿到宿主 mod 事件总线后调用。 */
    public static void attach(Object modEventBus) {
        IEventBus bus = (IEventBus) modEventBus;
        bus.addListener(RegisterCapabilitiesEvent.class, NeoForgeCapabilityBridge::onRegisterCapabilities);
        CapabilityRegistry.installHostBridge(new NeoForgeCapabilityBridge());
    }

    /** 把注册表里**此刻已有**的条目转登记进 NeoForge（事件只触发一次，之后的注册不会生效）。 */
    @SuppressWarnings("unchecked")
    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        int fluidBe = 0;
        int fluidBlock = 0;
        int energyBe = 0;
        int energyBlock = 0;

        for (CapabilityRegistry.FluidBlockEntityEntry entry : CapabilityRegistry.fluidBlockEntityEntries()) {
            BlockEntityType<BlockEntity> type = (BlockEntityType<BlockEntity>) entry.type();
            event.registerBlockEntity(Capabilities.Fluid.BLOCK, type, (blockEntity, side) -> {
                BrushFluidStorage storage = entry.provider().get(blockEntity, side);
                return storage == null ? null : new FluidAsResourceHandler(storage);
            });
            fluidBe++;
        }
        for (CapabilityRegistry.FluidBlockEntry entry : CapabilityRegistry.fluidBlockEntries()) {
            event.registerBlock(Capabilities.Fluid.BLOCK,
                    (level, pos, state, blockEntity, side) -> {
                        BrushFluidStorage storage = entry.provider().get(level, pos, state, blockEntity, side);
                        return storage == null ? null : new FluidAsResourceHandler(storage);
                    }, entry.block());
            fluidBlock++;
        }
        for (CapabilityRegistry.EnergyBlockEntityEntry entry : CapabilityRegistry.energyBlockEntityEntries()) {
            BlockEntityType<BlockEntity> type = (BlockEntityType<BlockEntity>) entry.type();
            event.registerBlockEntity(Capabilities.Energy.BLOCK, type, (blockEntity, side) -> {
                BrushEnergyStorage storage = entry.provider().get(blockEntity, side);
                return storage == null ? null : new EnergyAsHandler(storage);
            });
            energyBe++;
        }
        for (CapabilityRegistry.EnergyBlockEntry entry : CapabilityRegistry.energyBlockEntries()) {
            event.registerBlock(Capabilities.Energy.BLOCK,
                    (level, pos, state, blockEntity, side) -> {
                        BrushEnergyStorage storage = entry.provider().get(level, pos, state, blockEntity, side);
                        return storage == null ? null : new EnergyAsHandler(storage);
                    }, entry.block());
            energyBlock++;
        }

        LOGGER.info("[brush-api] S3 capability bridge: registered fluid(blockEntity=" + fluidBe
                + ", block=" + fluidBlock + ") energy(blockEntity=" + energyBe
                + ", block=" + energyBlock + ") into NeoForge Capabilities");
    }

    // ------------------------------------------------------------------ 宿主 → Brush

    @Override
    public BrushFluidStorage findFluid(Level level, BlockPos pos, Direction side) {
        ResourceHandler<FluidResource> handler = Capabilities.Fluid.BLOCK.getCapability(
                level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side);
        return handler == null ? null : new ResourceHandlerAsFluid(handler);
    }

    @Override
    public BrushEnergyStorage findEnergy(Level level, BlockPos pos, Direction side) {
        EnergyHandler handler = Capabilities.Energy.BLOCK.getCapability(
                level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side);
        return handler == null ? null : new HandlerAsEnergy(handler);
    }

    // ------------------------------------------------------------------ Brush → 宿主

    /** Brush 流体储罐 → NeoForge 增量 {@code ResourceHandler<FluidResource>}。 */
    static final class FluidAsResourceHandler implements ResourceHandler<FluidResource> {

        private final BrushFluidStorage storage;

        FluidAsResourceHandler(BrushFluidStorage storage) {
            this.storage = storage;
        }

        @Override
        public int size() {
            return Math.max(0, storage.tankCount());
        }

        @Override
        public FluidResource getResource(int index) {
            Fluid fluid = storage.fluidIn(index);
            return (fluid == null || fluid == Fluids.EMPTY) ? FluidResource.EMPTY : FluidResource.of(fluid);
        }

        @Override
        public long getAmountAsLong(int index) {
            return Math.max(0L, storage.amountIn(index));
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return Math.max(0L, storage.capacityOf(index));
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return storage.isValid(index, fluidOf(resource));
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            if (resource == null || resource.isEmpty() || amount <= 0) {
                return 0;
            }
            // 即刻生效：NeoForge 本版本的 TransactionContext 无回滚回调可挂（见类注释）。
            long moved = storage.insert(index, resource.getFluid(), amount, false);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, moved));
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            if (amount <= 0) {
                return 0;
            }
            long moved = storage.extract(index, fluidOf(resource), amount, false);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, moved));
        }
    }

    /** Brush 能量储能 → NeoForge {@code EnergyHandler}（{@code int} 宽度按需饱和截断）。 */
    static final class EnergyAsHandler implements EnergyHandler {

        private final BrushEnergyStorage storage;

        EnergyAsHandler(BrushEnergyStorage storage) {
            this.storage = storage;
        }

        @Override
        public long getAmountAsLong() {
            return Math.max(0L, storage.stored());
        }

        @Override
        public long getCapacityAsLong() {
            return Math.max(0L, storage.capacity());
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            if (amount <= 0 || !storage.canReceive()) {
                return 0;
            }
            long moved = storage.receive(amount, false);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, moved));
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            if (amount <= 0 || !storage.canExtract()) {
                return 0;
            }
            long moved = storage.extract(amount, false);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, moved));
        }
    }

    // ------------------------------------------------------------------ 宿主 handler 的 Brush 视图

    /** NeoForge 的增量 handler → Brush 流体储罐视图。 */
    static final class ResourceHandlerAsFluid implements BrushFluidStorage {

        private final ResourceHandler<FluidResource> handler;

        ResourceHandlerAsFluid(ResourceHandler<FluidResource> handler) {
            this.handler = handler;
        }

        @Override
        public int tankCount() {
            return Math.max(0, handler.size());
        }

        @Override
        public Fluid fluidIn(int tank) {
            FluidResource resource = handler.getResource(tank);
            return fluidOf(resource);
        }

        @Override
        public long amountIn(int tank) {
            return Math.max(0L, handler.getAmountAsLong(tank));
        }

        @Override
        public long capacityOf(int tank) {
            return Math.max(0L, handler.getCapacityAsLong(tank, handler.getResource(tank)));
        }

        @Override
        public boolean isValid(int tank, Fluid fluid) {
            return handler.isValid(tank, fluid == null || fluid == Fluids.EMPTY
                    ? FluidResource.EMPTY : FluidResource.of(fluid));
        }

        @Override
        public long insert(int tank, Fluid fluid, long maxAmount, boolean simulate) {
            if (fluid == null || fluid == Fluids.EMPTY || maxAmount <= 0) {
                return 0;
            }
            return transfer(true, tank, FluidResource.of(fluid), maxAmount, simulate);
        }

        @Override
        public long extract(int tank, Fluid fluid, long maxAmount, boolean simulate) {
            if (maxAmount <= 0) {
                return 0;
            }
            FluidResource resource = (fluid == null || fluid == Fluids.EMPTY)
                    ? handler.getResource(tank) : FluidResource.of(fluid);
            return transfer(false, tank, resource, maxAmount, simulate);
        }

        /**
         * 与宿主 handler 打交道：**始终由我们开一个根事务** —— 真实调用 {@code commit()}、模拟 {@code abort()}。
         *
         * <p>2026-10-16：去掉了对 {@code Transaction.getCurrentOpenedTransaction()} 的调用（宿主已弃用它）。
         * 代价与 Fabric 侧一致：**不参与外层事务**（Brush 接口既不接收、也看不见它）。若在宿主事务内调用
         * 本视图，{@code openRoot()} 会以宿主自己的方式报错 —— 那种场景请直接使用 NeoForge 的 handler。</p>
         */
        private long transfer(boolean insert, int tank, FluidResource resource, long maxAmount, boolean simulate) {
            int amount = (int) Math.min(Integer.MAX_VALUE, maxAmount);
            if (resource == null || resource.isEmpty() || amount <= 0) {
                return 0;
            }
            Transaction root = Transaction.openRoot();
            try {
                long moved = insert ? handler.insert(tank, resource, amount, root)
                        : handler.extract(tank, resource, amount, root);
                if (!simulate) {
                    root.commit();
                }
                // simulate：**不 commit** 直接 close ⇒ 回滚（NeoForge 的 Transaction 没有 abort()，
                // 回滚语义就是"关闭而不提交"，2026-10-16 编译期实证）。
                return Math.max(0L, moved);
            } finally {
                root.close();
            }
        }
    }

    /** NeoForge 的 {@code EnergyHandler} → Brush 能量储能视图。 */
    static final class HandlerAsEnergy implements BrushEnergyStorage {

        private final EnergyHandler handler;

        HandlerAsEnergy(EnergyHandler handler) {
            this.handler = handler;
        }

        @Override
        public long stored() {
            return Math.max(0L, handler.getAmountAsLong());
        }

        @Override
        public long capacity() {
            return Math.max(0L, handler.getCapacityAsLong());
        }

        @Override
        public long receive(long maxAmount, boolean simulate) {
            return transfer(true, maxAmount, simulate);
        }

        @Override
        public long extract(long maxAmount, boolean simulate) {
            return transfer(false, maxAmount, simulate);
        }

        /** 与 {@link HandlerAsEnergy} 同一套事务策略：自己开根事务，真实 commit、模拟 abort（见上方 transfer 的说明）。 */
        private long transfer(boolean insert, long maxAmount, boolean simulate) {
            int amount = (int) Math.min(Integer.MAX_VALUE, maxAmount);
            if (amount <= 0) {
                return 0;
            }
            Transaction root = Transaction.openRoot();
            try {
                long moved = insert ? handler.insert(amount, root) : handler.extract(amount, root);
                if (!simulate) {
                    root.commit();
                }
                // simulate：不 commit 直接 close ⇒ 回滚（同上）。
                return Math.max(0L, moved);
            } finally {
                root.close();
            }
        }
    }

    private static Fluid fluidOf(FluidResource resource) {
        if (resource == null || resource.isEmpty()) {
            return Fluids.EMPTY;
        }
        Fluid fluid = resource.getFluid();
        return fluid == null ? Fluids.EMPTY : fluid;
    }

}
