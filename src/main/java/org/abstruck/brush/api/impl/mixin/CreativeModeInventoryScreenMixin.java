package org.abstruck.brush.api.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.CreativeModeTab;

import org.abstruck.brush.api.BrushCreativeTabs;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 幻影高亮抑制 —— 创造屏对当前选中页签的无条件重绘中，跳过自有桥页签
 * （S1/S2；S3 下本包装为纯直通，宿主已自带守卫，docs/18 §7）。
 *
 * <p><b>归属</b>：创造栏是游戏能力，随 {@code brush-api} 库模组发布；自核心搬入时**一字未改**。
 * 本 mixin 只依赖 L2（{@link BrushCreativeTabs}）—— 严格说**连 §6 例外都不需要**，
 * 因为 L2 本就是 mod 的合法依赖面。</p>
 *
 * <p><b>织入语义（§10.1 / §7.2）</b>：{@code @WrapMethod}（MixinExtras 可链式整方法包装）——
 * handler 仅对自有桥提前返回，其余一律 {@code original.call} 透传；其他 mod 的同方法注入按
 * Mixin 常规规则共存。选址只依赖"方法存在"（§10.3 首选档）；{@code require = 1} 未命中即
 * 启动期硬失败。</p>
 *
 * <p><b>冷热</b>：热路径（渲染帧内约 15 次调用 / 帧）；代价 = 每调用 O(≤页签数) 恒等比较、无分配。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen")
public abstract class CreativeModeInventoryScreenMixin {

    /**
     * 目标：{@code extractTabButton} 的整方法包装 —— 自有桥页签跳过绘制，其余原样透传。
     */
    @WrapMethod(method = "extractTabButton(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/item/CreativeModeTab;)V",
            require = 1)
    private void brush$skipOwnBridgeTabButton(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
            CreativeModeTab tab, Operation<Void> original) {
        if (BrushCreativeTabs.tabOfBridge(tab) != null) {
            // 自有桥页签不参与原版槽位绘制；S3 下此判断恒假（宿主已守卫）—— 纯直通。
            return;
        }
        original.call(extractor, mouseX, mouseY, tab);
    }
}
