package org.abstruck.brush.api.impl.mixin;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.abstruck.brush.api.ServerPayloadEvent;
import org.abstruck.brush.net.BrushChannels;
import org.abstruck.brush.net.BrushPayload;
import org.abstruck.brush.net.ReplyGuard;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端载荷接收注入 —— L1 hook {@code network.server.payload}（docs/27 §2.7 发现 B 的补齐）。
 *
 * <p><b>为什么它在库模组里</b>：回包要构造 {@code ClientboundCustomPayloadPacket} 与核心内部件
 * {@code BrushPayload} / {@code BrushChannels} —— 普通 mod 按 §6 不得触碰 L1 / 内部件。
 * 由<b>库模组</b>（CLAUDE.md §6 例外允许引用 L1）实现这一层，对 mod 只暴露
 * {@link ServerPayloadEvent.Reply} 句柄，于是"双向通信"对 mod 仍然**只有 L2**。</p>
 *
 * <p><b>目标</b>：{@code ServerGamePacketListenerImpl#handleCustomPayload(ServerboundCustomPayloadPacket)}
 * 的 HEAD（javap 实证）。仅当载荷是本生态的 {@code BrushPayload} 实例时派发；vanilla 载荷零开销。</p>
 *
 * <p><b>织入语义（§10.1）</b>：HEAD 一处、仅 1 条 {@code Dispatcher.fire}；可链式 {@code @Inject}；
 * 判定失败即不派发，vanilla 控制流不受影响；{@code require = 1} 未命中即启动期硬失败。</p>
 *
 * <p><b>冷热</b>：非热路径（每收到一个自定义载荷包 1 次）。</p>
 */
@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl")
public abstract class ServerGamePacketListenerImplMixin {

    /**
     * 目标：{@code handleCustomPayload(ServerboundCustomPayloadPacket)} 的 HEAD。
     * 仅对 {@link BrushPayload} 实例派发，并附上回包句柄。
     */
    @Inject(
            method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
            at = @At("HEAD"),
            require = 1)
    private void brush$fireServerPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!(packet.payload() instanceof BrushPayload payload)) {
            return;
        }
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        ServerPayloadEvent.Reply reply = (channel, data) -> {
            // 校验语义（未注册 / 超长）抽到核心 net.ReplyGuard：那段此前只存在于本 lambda 里，
            // **只有真连接才能触发**，而 S2/S3 进不了世界 ⇒ 一条断言都没有（2026-10-14 用户指出）。
            // 抽成零 MC 引用件后由 ReplyGuardTest 钉住；此处只做"取上限 → 校验 → 发包"。
            int maxSize = BrushChannels.maxSizeOf(channel);
            // 宿主守卫（S3：未桥接进 NeoForge 注册表的通道在此可读地失败）+ 尺寸/注册校验
            BrushChannels.checkSendable(channel);
            ReplyGuard.check(channel.toString(), maxSize, data.length);
            // player.connection 是 ServerGamePacketListenerImpl（javap 实证）—— 直接沿用它回包，
            // 不额外缓存连接对象（避免生命周期问题）。
            player.connection.send(new ClientboundCustomPayloadPacket(new BrushPayload(channel, data)));
        };
        // 载荷契约 = (Reply, Identifier, byte[])（2026-10-15 改，同上：摆脱具体载荷类型）
        Dispatcher.fire(HookEvents.SERVER_PAYLOAD, reply, payload.id(), payload.data());
    }
}
