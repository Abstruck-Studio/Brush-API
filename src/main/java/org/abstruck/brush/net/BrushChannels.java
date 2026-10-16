package org.abstruck.brush.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brush 网络通道注册表 —— {@code Identifier → BrushPayload codec} 的进程级映射；
 * mod 经 {@link org.abstruck.brush.api.BrushNetwork} 注册，注入侧经 {@link #findCodecResult} 消费。
 *
 * <p><b>为什么是单一注册表 + 单点消费（可行性核心）</b>：vanilla 全部自定义载荷的
 * 编解码共享<b>唯一一个</b>单点 —— 匿名类 {@code net.minecraft.network.protocol.common.custom.CustomPacketPayload$1}
 * 的 {@code private StreamCodec findCodec(Identifier)}：
 * <ul>
 *   <li>{@code encode} → {@code writeCap} → 写 id → <b>findCodec(id)</b> → codec.encode；</li>
 *   <li>{@code decode} → 读 id → <b>findCodec(id)</b> → codec.decode；</li>
 * </ul>
 * 且 vanilla 与 NeoForge patched jar 的该类<b>同名同描述符同调用链</b>（patched 仅在 miss 后
 * 多一步 {@code NetworkRegistry.getCodec(id, protocol, flow)}，再落 {@code fallback.create}）。
 * 因此对 {@code findCodec} 的 RETURN 做一次 {@code @ModifyReturnValue} 即覆盖
 * <b>三运行时 × 双向 × config/play 全部协议阶段的全部调用点</b> —— 这是选它而非
 * "逐个包类注入 / lambda 定位"的原因（后者在 patched 侧因调用点数量差异会失配）。
 *
 * <p><b>优先级</b>：注册的通道优先于 {@code original}（vanilla idToType 命中项 /
 * NeoForge NetworkRegistry / DiscardedPayload fallback）。id 冲突（他方注册了同名通道）
 * 时我们胜出且不告警 —— 通道命名空间（如 {@code example:hello}）由 mod 自行负责，
 * 与生态惯例一致。
 *
 * <p><b>线程模型</b>：注册任一线程（CHM）；查找在编解码路径（游戏主线程 / netty 线程均可能）。
 */
public final class BrushChannels {

    /** 通道 id → codec + 上限（注册表；键集经 {@link #registeredIds()} 只读暴露）。 */
    private static final ConcurrentHashMap<Identifier, ChannelEntry> CHANNELS = new ConcurrentHashMap<>();

    private BrushChannels() {
    }

    /**
     * 注册通道。重复 id / 非法上限 = {@code IllegalArgumentException}（响亮失败，不静默覆盖）。
     *
     * @param id      通道 id
     * @param maxSize 单载荷上限（1..{@link BrushPayload#MAX_PAYLOAD_SIZE}）
     */
    public static void register(Identifier id, int maxSize) {
        if (maxSize <= 0 || maxSize > BrushPayload.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("channel " + id + ": maxSize " + maxSize
                    + " out of range 1.." + BrushPayload.MAX_PAYLOAD_SIZE);
        }
        ChannelEntry previous = CHANNELS.putIfAbsent(id, new ChannelEntry(BrushPayload.codec(id, maxSize), maxSize));
        if (previous != null) {
            throw new IllegalArgumentException("channel already registered: " + id);
        }
    }

    /** 通道是否已注册。 */
    public static boolean isRegistered(Identifier id) {
        return CHANNELS.containsKey(id);
    }

    /** 已注册通道的上限；未注册返回 -1（{@code BrushNetwork.send} 的尺寸预检用）。 */
    public static int maxSizeOf(Identifier id) {
        ChannelEntry entry = CHANNELS.get(id);
        return entry == null ? -1 : entry.maxSize();
    }

    /**
     * {@code CustomPacketPayload$1#findCodec} 的 RETURN 包装体（见类 javadoc）：
     * 已注册通道返回本表 codec，否则原样透传（{@code original} 在两种运行时都非 null ——
     * 两侧都以 {@code fallback.create} 收尾，patched 侧多一步 NetworkRegistry）。
     */
    public static StreamCodec<?, ?> findCodecResult(Identifier id, StreamCodec<?, ?> original) {
        ChannelEntry entry = CHANNELS.get(id);
        return entry != null ? entry.codec() : original;
    }

    /** 已注册通道 id 快照（自检报告与探针用）。 */
    public static Set<Identifier> registeredIds() {
        return Set.copyOf(CHANNELS.keySet());
    }

    /**
     * 宿主侧安装的"可发送性"守卫（仅宿主适配器使用；{@code null} = 无守卫，S1/S2 常态）。
     *
     * <p><b>为什么需要它</b>：S3（NeoForge）要求载荷先经宿主注册表登记，而宿主只给**一次**登记机会
     * （其 {@code RegisterPayloadHandlersEvent}）。因此"登记事件之后才登记的通道"在 S3 下发不出去
     * （实机现场见 docs/29 §6.5；2026-10-15 由独立评审发现、我方复核）。装上守卫后，
     * 这种情形会变成 <b>Brush 自己的可读硬失败</b>，而不是让 NeoForge 抛出难懂的
     * {@code UnsupportedOperationException}，也不是静默失效（§7.1 形态 B 那类最坏情形）。</p>
     */
    @FunctionalInterface
    public interface SendGuard {

        /**
         * @param channel 待发送的通道
         * @return 不可发送的原因（进异常消息）；{@code null} = 允许发送
         */
        String rejectionReason(Identifier channel);
    }

    /** 当前守卫（宿主适配器装配；volatile 因为装配与发送可能不同线程）。 */
    private static volatile SendGuard sendGuard;

    /** 安装 / 清除发送守卫（宿主适配器专用；传 {@code null} 清除）。 */
    public static void setSendGuard(SendGuard guard) {
        sendGuard = guard;
    }

    /**
     * 发送前校验（{@code BrushNetwork.send} 与回包路径调用）。
     *
     * @throws IllegalStateException 宿主守卫判定该通道当前不可发送（原因由守卫给出，含补救指引）
     */
    public static void checkSendable(Identifier channel) {
        SendGuard guard = sendGuard;
        if (guard == null) {
            return;
        }
        String reason = guard.rejectionReason(channel);
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
    }

    /** 通道条目：codec 与其上限（上限供 send 侧预检，解码侧上限已烙进 codec）。 */
    private record ChannelEntry(StreamCodec<FriendlyByteBuf, BrushPayload> codec, int maxSize) {
    }
}
