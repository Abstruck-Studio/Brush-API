package org.abstruck.brush.net;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

import org.abstruck.brush.api.BrushNetwork;

/**
 * 网络引擎实现（2026-10-15，docs/26 §7.7）。
 *
 * <p><b>为什么有这一层</b>：引擎随能力一起住在**库模组** {@code brush-api}，而对外入口
 * {@link BrushNetwork} 必须以**门面**形式留在核心 —— {@code org.abstruck.brush.api} 是核心模块
 * 拥有的包，宿主（FML）加载的代码只会去该模块里找该包的类，找不到也不会回退到我们的 mod 加载器
 * （机理见 {@code host.neoforge.ModJarFallbackLoader} 的类 javadoc）。故本类实现门面的
 * {@link BrushNetwork.Impl}，由 {@code BrushApiMod} 在初始化期装入；门面另有惰性解析兜底，
 * 因此**装载顺序无关**。</p>
 *
 * <p>逻辑与搬家前逐字一致（只是归属从核心移到库模组）：注册落进 {@link BrushChannels}；
 * 发送走 vanilla 原生 {@code ServerboundCustomPayloadPacket}，发送前过
 * {@link BrushChannels#checkSendable}（S3 的宿主守卫）。</p>
 */
public final class BrushNetworkImpl implements BrushNetwork.Impl {

    @Override
    public int defaultMaxSize() {
        return BrushPayload.MAX_PAYLOAD_SIZE;
    }

    @Override
    public void register(Identifier channel, int maxSize) {
        BrushChannels.register(channel, maxSize);
    }

    @Override
    public void send(Identifier channel, byte[] data) {
        int maxSize = BrushChannels.maxSizeOf(channel);
        if (maxSize < 0) {
            throw new IllegalStateException("channel not registered: " + channel
                    + " (call BrushNetwork.register first)");
        }
        if (data.length > maxSize) {
            throw new IllegalArgumentException("payload for " + channel + ": " + data.length
                    + " bytes exceeds channel limit " + maxSize);
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft == null ? null : minecraft.getConnection();
        if (connection == null) {
            throw new IllegalStateException("not connected to a server"
                    + " (check Minecraft.getConnection() != null before sending)");
        }
        // 宿主守卫（S3：未桥接进 NeoForge 注册表的通道在这里**可读地**失败；S1/S2 无守卫）
        BrushChannels.checkSendable(channel);
        connection.send(new ServerboundCustomPayloadPacket(new BrushPayload(channel, data)));
    }
}
