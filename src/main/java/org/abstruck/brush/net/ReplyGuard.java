package org.abstruck.brush.net;

/**
 * 回包（{@code ServerPayloadEvent.Reply}）的**校验语义**（零 MC 引用，故可被 {@code src/test} 直接单测）。
 *
 * <p>为什么要单独成类：回包的两个失败路径（**通道未注册** / **载荷超过该通道上限**）此前写在
 * 库模组 mixin 的 lambda 里 —— 那是**只有真连接才能触发的代码**，S2/S3 又进不了世界，
 * 于是这段语义**一条断言都没有**。抽到这里后，两条失败路径与边界条件都能在单测里钉住；
 * mixin 只剩"取通道上限 → 交给本类校验 → 发包"三步。</p>
 *
 * <p><b>语义（与 mixin 内联版逐字一致）</b>：</p>
 * <ul>
 *   <li>{@code maxSize < 0} ⇒ {@link IllegalStateException}（未注册；{@code BrushChannels.maxSizeOf}
 *       对未注册通道返回 -1）；</li>
 *   <li>{@code dataLength > maxSize} ⇒ {@link IllegalArgumentException}（超长）；</li>
 *   <li>相等（{@code dataLength == maxSize}）**允许** —— 上限是"含"的。</li>
 * </ul>
 *
 * <p><b>为什么用异常而不是静默丢弃</b>：静默丢弃正是 CLAUDE.md §7.1 形态 B 那类"不报错的失效"，
 * mod 作者会以为包发出去了。宁可响亮失败。</p>
 */
public final class ReplyGuard {

    private ReplyGuard() {
    }

    /**
     * 校验一次回包请求。
     *
     * @param channel    通道的可读名（{@code Identifier#toString()}）
     * @param maxSize    该通道的注册上限；**负数 = 未注册**（见 {@code BrushChannels.maxSizeOf}）
     * @param dataLength 回包载荷长度
     * @throws NullPointerException     channel 为 null
     * @throws IllegalStateException    通道未注册
     * @throws IllegalArgumentException 载荷超长
     */
    public static void check(String channel, int maxSize, int dataLength) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        if (maxSize < 0) {
            throw new IllegalStateException("reply channel not registered: " + channel
                    + " (call BrushNetwork.register first)");
        }
        if (dataLength > maxSize) {
            throw new IllegalArgumentException("reply payload for " + channel + ": "
                    + dataLength + " bytes exceeds channel limit " + maxSize);
        }
    }
}
