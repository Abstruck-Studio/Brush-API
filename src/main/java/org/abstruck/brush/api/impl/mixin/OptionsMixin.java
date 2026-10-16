package org.abstruck.brush.api.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

import org.abstruck.brush.keybind.KeybindRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 键位拼接注入 —— 把 {@code BrushKeybinds} 收下的映射接进 {@code Options.keyMappings}。
 *
 * <h2>为什么锚在"产生该数组的那个表达式"上（javap 实证，26.1.2）</h2>
 *
 * <p>{@code Options#<init>(Minecraft, File)} 里 vanilla 用自己那批字段经
 * {@code Stream.of(…, keyHotbarSlots, debugKeys).flatMap(…).toArray(…)} 建出数组，
 * 紧接 {@code putfield keyMappings}（偏移 4081）。两个关键事实：</p>
 * <ul>
 *   <li>{@code keyMappings} 是 <b>{@code public final KeyMapping[]}</b>；</li>
 *   <li>构造器在偏移 <b>4959</b> 处还会调用 {@code load()}，而 {@code load()} 会走
 *       {@code processOptions} <b>读</b>这张表去载入已存键位 —— 即"读"发生在同一构造器内、
 *       且晚于那次 putfield。</li>
 * </ul>
 *
 * <p>⇒ <b>不能在构造结束（TAIL）再补写</b>：那样映射能列出但**已保存的键码不会被载入**。</p>
 *
 * <h2>为什么不是 {@code @WrapOperation} 包那次 putfield（实测撞墙，记录在案）</h2>
 *
 * <p>本类最初写成 {@code @WrapOperation} 包 PUTFIELD，启动即崩：</p>
 *
 * <pre>
 * java.lang.IllegalAccessError: Update to non-static final field
 *   net.minecraft.client.Options.keyMappings attempted from a different method
 *   (mixinextras$bridge$keyMappings$276) than the initializer method
 * </pre>
 *
 * <p>原因是 JVM 层面的硬限制：<b>非静态 final 字段只允许在"初始化它的那个方法"（即构造器）内写入</b>，
 * 而字段写包装会生成一个**桥方法**去执行 PUTFIELD → 必然非法。<b>任何</b>对 final 实例字段的
 * 字段写包装都不可行，与签名无关。因此改为修改<b>值的来源</b>：让 PUTFIELD 仍留在构造器里，
 * 只在它之前把数组接长。</p>
 *
 * <p><b>为什么用 {@code @ModifyExpressionValue} 而不是 {@code @Redirect}</b>：§7.2 ——
 * 可链式。S2/S3 下 Fabric 的 {@code OptionsMixin}（{@code KeyMappingRegistryImpl.process}）
 * 与 NeoForge 的补丁也在动这张表；都是"把数组接长"，可链式才能叠加。</p>
 *
 * <p><b>目标唯一性</b>：{@code <init>} 内 {@code Stream.toArray} 仅出现一处（javap 实测），
 * 故该目标不会误伤 {@code keyHotbarSlots} / {@code debugKeys} 等其它数组。
 * {@code require = 1}：未命中即启动期硬失败（§7.3）。</p>
 */
@Mixin(targets = "net.minecraft.client.Options")
public abstract class OptionsMixin {

    /**
     * 目标：{@code Options#<init>(Minecraft, File)} 内
     * {@code Stream.toArray(IntFunction)} 的返回值（即随后被 {@code putfield keyMappings} 的那个数组）。
     *
     * <p>入参/返回类型是 {@code Object[]}（vanilla 随后 {@code checkcast} 到
     * {@code KeyMapping[]}）—— 返回更长的 {@code KeyMapping[]} 即可通过该 checkcast。</p>
     */
    @ModifyExpressionValue(
            method = "<init>(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Stream;toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"),
            require = 1)
    private static Object[] brush$spliceKeybinds(Object[] vanillaKeyMappings) {
        return KeybindRegistry.INSTANCE.splice((KeyMapping[]) vanillaKeyMappings);
    }
}
