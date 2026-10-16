package org.abstruck.brush.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Brush 通道载荷 —— {@code CustomPacketPayload} 的字节数组实现。
 *
 * <p><b>形态（对齐 vanilla 的官方载荷设计）</b>：vanilla 对"未知通道"用
 * {@code DiscardedPayload}（record，codec(id, maxSize)：decode 读剩余字节并做上限校验，
 * 超限抛 {@code IllegalArgumentException}；encode 抛不支持）。本类是它的"可编解码对偶"：
 * 同一 record 形态 + 同型 codec，供我们自己的通道做真实收发。
 *
 * <p><b>包级位置</b>：核心内部件（{@code org.abstruck.brush.net}）—— mod 经
 * {@link org.abstruck.brush.api.BrushNetwork}（api 层）使用，不直接接触本类；
 * 注入侧（{@code CustomPacketPayloadCodecMixin}）与投递侧（{@code ClientCommonPacketListenerMixin}）
 * 消费本类型。本类只是载荷载体，不含语义事件（L1/L2 分层不在此处）。
 *
 * <p><b>{@code data()} 的相等性</b>：record 自动生成的 {@code equals}/{@code hashCode} 对数组是
 * 引用比较 —— 判等用 {@code java.util.Arrays.equals(a.data(), b.data())}（自检探针口径）。
 *
 * @param id   通道 id（= 包内传输的 Identifier）
 * @param data 原始字节（decode 时长度已按通道上限校验）
 */
public record BrushPayload(Identifier id, byte[] data) implements CustomPacketPayload {

    /** 单载荷上限字节数。镜像 vanilla {@code MAX_PAYLOAD_SIZE = 1048576}（Serverbound /
     * ClientboundCustomPayloadPacket 同值），{@link org.abstruck.brush.api.BrushNetwork#register}
     * 的默认值。 */
    public static final int MAX_PAYLOAD_SIZE = 1_048_576;

    public BrushPayload {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
    }

    /**
     * 通道 codec（每次 {@link BrushChannels#register} 构造一份，注册表长期持有）。
     *
     * <p><b>encode</b>：写原始字节（vanilla 包框由外层 codec 负责 —— 每个自定义载荷的 codec
     * 拥有包内剩余字节，与 vanilla 的 DiscardedPayload 同一契约）。
     * <b>decode</b>：读剩余全部字节；{@code size < 0 || size > maxSize} 抛
     * {@code IllegalArgumentException} —— 与 vanilla {@code DiscardedPayload.codec} 的拒绝
     * 分支同型（{@code readableBytes} → 负 / 超限 → 抛 {@code IllegalArgumentException}）。
     */
    public static StreamCodec<FriendlyByteBuf, BrushPayload> codec(Identifier id, int maxSize) {
        return StreamCodec.of(
                (buf, payload) -> buf.writeBytes(payload.data()),
                buf -> {
                    int size = buf.readableBytes();
                    if (size < 0 || size > maxSize) {
                        throw new IllegalArgumentException("brush channel " + id
                                + ": payload size " + size + " exceeds limit " + maxSize);
                    }
                    byte[] data = new byte[size];
                    buf.readBytes(data);
                    return new BrushPayload(id, data);
                });
    }

    /** 载荷类型（= 本通道 id；encode 路径写的就是它 —— {@code writeCap} 读 {@code type().id()}）。 */
    @Override
    public Type<BrushPayload> type() {
        return new Type<>(id);
    }
}
