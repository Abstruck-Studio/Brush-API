package org.abstruck.brush.api.impl.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD 渲染注入 —— L1 hook {@code hud.extract}（docs/19 §9）。
 *
 * <p><b>归属</b>：HUD 是游戏能力，随 {@code brush-api} 库模组发布（CLAUDE.md §6 例外允许引用 L1）。
 * 自核心搬入时**织入内容一字未改**，只是归属变了（docs/26 §7.6 同款做法）。</p>
 *
 * <p><b>目标</b> = {@code Gui.extractRenderState(GuiGraphicsExtractor, DeltaTracker)}（Gui 直接声明）：
 * 把该帧全部 vanilla HUD 元素写入 extractor。<b>TAIL 语义</b> = "本帧 vanilla HUD 已完整提取" ——
 * 监听器可在其上继续追加绘制（§10.3 第 2 条首选档：只依赖方法存在）。</p>
 *
 * <p><b>冲突面</b>：fabric-api 对 Gui 的注入全部为 {@code @WrapOperation} 且位于方法内部调用点上，
 * 与本 TAIL {@code @Inject} 位置不同、任意先后互不抹除（§7.2 可链式）。</p>
 *
 * <p>织入语义（§10.1）：仅 1 条 {@code Dispatcher.fire}（双参重载）；不改控制流、不加字段；
 * {@code require = 1} 未命中即启动期硬失败。热路径（每渲染帧 1 次）：无监听器时 = 一次 CHM 查找
 * + 空判断（零分配），论证同核心版本。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public abstract class GuiMixin {

    /**
     * 目标：{@code Gui.extractRenderState(GuiGraphicsExtractor, DeltaTracker)} 的 TAIL
     * （vanilla HUD 元素已全部写入 extractor）。
     */
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"),
            require = 1)
    private void brush$fireHudExtract(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        Dispatcher.fire(HookEvents.HUD_EXTRACT, extractor, deltaTracker);
    }
}
