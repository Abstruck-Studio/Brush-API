package org.abstruck.brush.api.impl.mixin;

import net.minecraft.client.input.KeyEvent;

import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 键盘输入注入 —— L1 hook {@code input.key.press}（docs/19 §9）。
 *
 * <p><b>归属</b>：输入是游戏能力，随 {@code brush-api} 库模组发布（CLAUDE.md §6 例外允许引用 L1）。
 * 自核心搬入时**织入内容一字未改**。</p>
 *
 * <p><b>目标</b> = {@code KeyboardHandler.keyPress(long, int, KeyEvent)}：GLFW 键盘回调经
 * {@code Minecraft.execute} 在主线程转发进本方法入口。方法体第一动作是窗口句柄守卫，
 * HEAD 注入先于守卫执行 —— 探针可借伪句柄 live-fire 而对 vanilla 体零副作用
 * （{@code SelftestInputHudProbe}）。</p>
 *
 * <p>织入语义（§10.1）：仅 1 条 {@code Dispatcher.fire}（载荷 [KeyEvent, action]，不含窗口句柄）；
 * 可链式 {@code @Inject}；{@code require = 1} 未命中即启动期硬失败。非热路径但可突发。</p>
 */
@Mixin(targets = "net.minecraft.client.KeyboardHandler")
public abstract class KeyboardHandlerMixin {

    /**
     * 目标：{@code keyPress} 的 HEAD（先于窗口句柄守卫）。{@code action} = GLFW 动作值
     * （0=RELEASE / 1=PRESS / 2=REPEAT）。
     */
    @Inject(
            method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V",
            at = @At("HEAD"),
            require = 1)
    private void brush$fireInputKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        Dispatcher.fire(HookEvents.INPUT_KEY_PRESS, event, action);
    }
}
