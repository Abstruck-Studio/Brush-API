package org.abstruck.brush.api.impl.mixin;

import net.minecraft.client.input.MouseButtonInfo;

import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标输入注入 —— L1 hook {@code input.mouse.button} / {@code input.mouse.scroll}（docs/19 §9）。
 *
 * <p><b>归属</b>：输入是游戏能力，随 {@code brush-api} 库模组发布（CLAUDE.md §6 例外允许引用 L1）。
 * 自核心搬入时**织入内容一字未改**。</p>
 *
 * <p><b>目标</b> = {@code MouseHandler.onButton(long, MouseButtonInfo, int)} 与
 * {@code onScroll(long, double, double)}。两方法体头部均有窗口句柄守卫，HEAD 注入先于守卫执行 ——
 * 探针可借伪句柄 live-fire 而对 vanilla 体零副作用（{@code SelftestInputHudProbe}）。</p>
 *
 * <p>织入语义（§10.1）：两个方法各仅 1 条 {@code Dispatcher.fire}（载荷不含窗口句柄）；
 * 可链式 {@code @Inject}；{@code require = 1} 未命中即启动期硬失败。非热路径，无监听器时零分配。</p>
 */
@Mixin(targets = "net.minecraft.client.MouseHandler")
public abstract class MouseHandlerMixin {

    /**
     * 目标：{@code onButton} 的 HEAD（先于窗口句柄守卫）。{@code action} = GLFW 动作值
     * （0=RELEASE / 1=PRESS）。
     */
    @Inject(
            method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("HEAD"),
            require = 1)
    private void brush$fireInputMouseButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        Dispatcher.fire(HookEvents.INPUT_MOUSE_BUTTON, info, action);
    }

    /**
     * 目标：{@code onScroll} 的 HEAD（先于窗口句柄守卫）。载荷 = 原始滚轮增量 (xOffset, yOffset)。
     */
    @Inject(
            method = "onScroll(JDD)V",
            at = @At("HEAD"),
            require = 1)
    private void brush$fireInputMouseScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        Dispatcher.fire(HookEvents.INPUT_MOUSE_SCROLL, xOffset, yOffset);
    }
}
