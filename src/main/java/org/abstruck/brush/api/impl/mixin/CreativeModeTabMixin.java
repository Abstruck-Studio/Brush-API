package org.abstruck.brush.api.impl.mixin;

import java.util.Collection;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 创造模式物品栏注入 —— L1 hook {@code creative.tab.output}（docs/14 §9）。
 *
 * <p><b>归属</b>：创造栏是游戏能力，随 {@code brush-api} 库模组发布（CLAUDE.md §6 例外允许引用 L1）；
 * 自核心搬入时**织入内容一字未改**。</p>
 *
 * <p><b>为什么是 buildContents 的 TAIL</b>：方法体 = 建 {@code ItemDisplayBuilder} → 生成器 accept →
 * 把 builder 的两个内容集合写回实例字段。TAIL 时 {@code displayItems} 与
 * {@code displayItemsSearchTab} <b>均已赋值完毕</b>，handler 直接读实例字段即拿到完整内容引用
 * （对集合原位追加即生效）。TAIL 选址 = CLAUDE.md §10.3 第 2 条首选。</p>
 *
 * <p><b>{@code @Shadow} 不是加字段</b>（§10.2 澄清）：{@code @Shadow} 是编译期别名，指向目标类
 * <b>已有</b>字段，不向 vanilla 类新增任何成员 —— 本 mixin 无 {@code @Unique}，vanilla 类结构改动为 0。
 * 这一点与 mixin 住在哪个 jar 无关。</p>
 *
 * <p>织入语义（§10.1）：仅 1 条 {@code Dispatcher.fire} 静态调用，走 4 参重载。载荷：[0] =
 * {@code ResourceKey<CreativeModeTab>}（26.1.2 的 {@code getKey} 返回 {@code Identifier} 而非
 * {@code ResourceKey}，用错会被 L2 键相等门静默滤掉；未注册返回 null 则无人投递，语义自洽）、
 * [1] = {@code ItemDisplayParameters}、[2] = {@code Collection<ItemStack>}（可写）、
 * [3] = {@code Set<ItemStack>}（可写）。handler 不自行做任何工作（薄注入）。</p>
 *
 * <p><b>冷热</b>：非热路径但可突发 —— 每次 {@code buildContents} 调用一次（标签页首建 /
 * 重开创造界面重建 / 搜索刷新批量重建）。</p>
 */
@Mixin(targets = "net.minecraft.world.item.CreativeModeTab")
public abstract class CreativeModeTabMixin {

    /** 目标类已有字段：{@code private Collection<ItemStack> displayItems;}。 */
    @Shadow
    private Collection<ItemStack> displayItems;

    /** 目标类已有字段：{@code private Set<ItemStack> displayItemsSearchTab;}。 */
    @Shadow
    private Set<ItemStack> displayItemsSearchTab;

    /** 目标：{@code CreativeModeTab.buildContents(ItemDisplayParameters)} 的 TAIL（两个内容集合赋值完毕）。 */
    @Inject(
            method = "buildContents(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V",
            at = @At("TAIL"),
            require = 1)
    private void brush$fireCreativeTabOutput(CreativeModeTab.ItemDisplayParameters parameters, CallbackInfo ci) {
        Dispatcher.fire(
                HookEvents.CREATIVE_TAB_OUTPUT,
                // getResourceKey 返回 Optional<ResourceKey<CreativeModeTab>>；26.1.2 的 getKey
                // 返回的是 Identifier —— L2 约定载荷为 ResourceKey（HookEvents javadoc），
                // 用错会被键相等门静默滤掉。
                BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey((CreativeModeTab) (Object) this)
                        .orElse(null),
                parameters,
                this.displayItems,
                this.displayItemsSearchTab);
    }
}
