package org.abstruck.brush.api.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import org.abstruck.brush.net.BrushChannels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 自定义载荷编解码单点包装 —— Brush 通道 codec 的接入点（docs/19 §8）。
 *
 * <p><b>为什么它在库模组里</b>：同 {@link ClientCommonPacketListenerMixin} —— 网络能力随
 * {@code brush-api} 拆分，按 CLAUDE.md §6 例外条款（2026-10-14 用户拍板）允许引用核心内部。</p>
 *
 * <p><b>目标</b>：{@code CustomPacketPayload} 编译出的匿名类 {@code CustomPacketPayload$1}
 * （实现 {@code StreamCodec<B, CustomPacketPayload>}）中，vanilla 与 NeoForge patched jar
 * <b>同名同描述符</b>的私有方法 {@code findCodec(Identifier)} —— 全部自定义载荷的编解码都经此
 * 单点（encode → findCodec；decode → findCodec），两侧返回值都非 null。Clientbound /
 * Serverbound 自定义载荷包各自的 STREAM_CODEC 都包着同一个 {@code CustomPacketPayload.STREAM_CODEC}
 * 实例（= 此匿名类），因此本包装自动覆盖<b>三运行时 × 双向 × config/play 全部协议阶段</b>。</p>
 *
 * <p>织入语义（§10.1 / §7.2）：{@code @ModifyReturnValue}（MixinExtras 可链式包装类注入器）
 * 在 findCodec 的 RETURN 处包装返回值；新返回值经 {@link BrushChannels#findCodecResult}
 * （注册通道优先，未注册原样透传）。<b>本点不派发 Dispatcher 事件</b>（codec 选择是纯映射，
 * 无 mod 语义回调）。不改控制流、不加字段；{@code require = 1} 未命中即启动期硬失败。</p>
 *
 * <p><b>冲突面</b>：fabric-api 的 {@code CustomPayloadStreamCodecMixin} 以 {@code @WrapOperation}
 * 包装 findCodec 的<b>调用点</b>，与我们的 findCodec 方法体 RETURN 位置不同，任意先后互不抹除。</p>
 *
 * <p>冷热：非热路径（每次载荷编/解码 1 次），无注册通道时代价 = 一次 CHM 查找。</p>
 */
@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public abstract class CustomPacketPayloadCodecMixin {

    /**
     * 目标：{@code CustomPacketPayload$1.findCodec(Identifier)} 的 RETURN。handler 为静态形态：
     * 参数 = 原返回值（original，首位）+ 捕获的目标参数（id）。
     */
    @ModifyReturnValue(
            method = "findCodec(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/network/codec/StreamCodec;",
            at = @At("RETURN"),
            require = 1)
    private static StreamCodec<?, ?> brush$registeredChannel(StreamCodec<?, ?> original, Identifier id) {
        return BrushChannels.findCodecResult(id, original);
    }
}
