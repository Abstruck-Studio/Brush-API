package org.abstruck.brush.api.impl.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;

import org.abstruck.brush.net.BrushPayload;
import org.abstruck.brush.net.DispatchDedupe;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brush 通道载荷投递注入 —— L1 hook {@code network.payload}（docs/19 §8）。
 *
 * <p><b>为什么它在库模组里而不是核心</b>（Phase C，docs/26 §7）：网络是"游戏能力"，
 * 按 Fabric Loader / Fabric API 的分工应从加载器核心拆到第一方能力库。本类随
 * {@code brush-api} 以<b>库模组</b>身份发布，因此按 CLAUDE.md §6 的例外条款
 * （2026-10-14 用户拍板）**允许引用 L1** —— 这正是拆分的可行性前提：
 * 能力实现与其注入点天然与 vanilla 强耦合，搬到独立 jar 时不可能同时避开 L1。</p>
 *
 * <p><b>目标</b> = {@code ClientCommonPacketListenerImpl.handleCustomPayload(ClientboundCustomPayloadPacket)}，
 * 客户端自定义载荷包的唯一公开入口（服务端→客户端方向）。同类另有 abstract 重载
 * {@code handleCustomPayload(CustomPacketPayload)}，注入必须写全描述符。</p>
 *
 * <p><b>织入语义（§10.1）</b>：HEAD 一处、仅 1 条 {@code Dispatcher.fire}；{@code instanceof BrushPayload}
 * 的类型判定在织入片段内 —— 判定失败即不派发，vanilla 控制流不受影响；可链式 {@code @Inject}；
 * {@code require = 1} 未命中即启动期硬失败。**与核心版本逐字节同源** —— 搬的只是归属，
 * 织入内容一字未改。</p>
 *
 * <h2>去重与线程（2026-10-14，docs/27 §2.7.3 实测修正）</h2>
 *
 * <p><b>实测事实</b>：26.1.2 下本地（集成服务器）连接会把**同一个**
 * {@code ClientboundCustomPayloadPacket} <b>实例</b>处理两次 ——
 * 一次在 netty 线程内联（{@code Connection.channelRead0}），
 * 一次在主线程排队（{@code PacketProcessor.processQueuedPackets} ← {@code Minecraft.runTick}）。
 * 两次调用的 {@code System.identityHashCode(packet)} 完全相同（实测）。</p>
 *
 * <p>因此本注入做两件事，都是**必需**的：</p>
 * <ol>
 *   <li><b>按包实例身份去重</b>：同一次逻辑投递只派发一次。这不是优化 ——
 *       不去重则每个载荷都被投递两次，使用方会重复执行副作用。
 *       去重逻辑在核心 {@link DispatchDedupe}（**有界身份窗口**，可单测）。
 *       <b>注意：单槽（只记最后一个）是不够的</b> —— 两个包在一个 tick 内交错时，
 *       先到的那个会在排队路径上被二次放行（推导与回归测试见 {@link DispatchDedupe} javadoc）。</li>
 *   <li><b>统一在主线程投递</b>：若命中内联路径（netty 线程），转投主线程队列后派发。
 *       与 Brush 其余 L2 事件的线程语义一致 —— 否则 mod 监听器可能在网络线程上执行、
 *       直接触碰游戏状态。代价：内联路径的派发会推迟到主线程下一次处理队列时。</li>
 * </ol>
 *
 * <p><b>代价与如实登记</b>：{@link DispatchDedupe} 会强引用最近
 * {@link DispatchDedupe#DEFAULT_CAPACITY} 个包对象（及其缓冲区）—— 有界（不随运行时长增长）。
 * 这是"一点内存换取恰好一次 + 线程确定"的显式取舍，写在 {@link DispatchDedupe} javadoc 里，
 * 不埋着。</p>
 *
 * <p><b>冷热</b>：非热路径（每次收到一个自定义载荷包 1 次）。</p>
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl")
public abstract class ClientCommonPacketListenerMixin {

    /** 载荷派发去重（有界身份窗口；见类 javadoc 与 {@link DispatchDedupe}）。 */
    private static final DispatchDedupe DEDUPE = new DispatchDedupe();

    /**
     * 目标：{@code handleCustomPayload(ClientboundCustomPayloadPacket)} 的 HEAD（全描述符，
     * 区分 abstract 重载）。仅对 {@link BrushPayload} 实例派发，且同一包实例只派发一次、
     * 且在游戏主线程上派发。
     */
    @Inject(
            method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"),
            require = 1)
    private void brush$fireNetworkPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!(packet.payload() instanceof BrushPayload payload)) {
            return;
        }
        if (!DEDUPE.claim(packet)) {
            return;   // 同一包实例的另一条处理路径：不重复派发
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && !minecraft.isSameThread()) {
            // 内联（网络线程）路径 → 转投主线程，保证监听器永远在主线程被调用
            minecraft.execute(() -> Dispatcher.fire(HookEvents.NETWORK_PAYLOAD,
                    payload.id(), payload.data()));
        } else {
            // 载荷契约 = (Identifier, byte[])（2026-10-15 改）：核心 L2 桥因此不再持有
            // 具体载荷类型 BrushPayload —— docs/26 §7.6 的第①条后续（为网络引擎整体搬进库模组铺路）。
            Dispatcher.fire(HookEvents.NETWORK_PAYLOAD, payload.id(), payload.data());
        }
    }
}
