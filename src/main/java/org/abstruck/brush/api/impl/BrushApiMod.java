package org.abstruck.brush.api.impl;

import org.abstruck.brush.api.BrushModInitializer;
import org.abstruck.brush.api.BrushNetwork;
import org.abstruck.brush.host.HostSeams;
import org.abstruck.brush.net.BrushNetworkImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@code Brush API} mod 的入口（Phase C，docs/26 §4）。
 *
 * <p><b>它是什么</b>：Brush 的<b>第一方 mod</b>。核心只保留加载器本职，游戏能力型 API
 * （内容注册 / 网络 / 创造栏 / HUD 与输入 / 屏幕事件 …）搬到这里 —— 对应 Fabric 世界里
 * {@code Fabric Loader} 与 {@code Fabric API} 的分工（docs/26 §2）。</p>
 *
 * <p><b>当前阶段 = C2a 骨架</b>：本类此刻只做"能被加载并初始化"这一件事，用来先证明
 * <b>第二个第一方 mod 在三个运行时都能装载</b>；能力类在 C2b 起才逐步搬入。
 * 这样拆分是为了让每一步都可单独验证（CLAUDE.md §3.7 小步产出）——
 * 若把"建骨架"和"搬能力"混在一轮，出问题时分不清是装载接线错了还是搬错了。</p>
 *
 * <p><b>包名约定（重要）</b>：能力类搬过来之后<b>保持原有包名与类名</b>
 * （如 {@code org.abstruck.brush.api.BrushNetwork}），因此 **mod 作者的 import 一行都不用改**。
 * 本入口类放在 {@code .api.impl} 子包，是为了不与对外 API 表面混在一起。</p>
 */
public final class BrushApiMod implements BrushModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("BrushApi");

    @Override
    public void onInitialize() {
        LOGGER.info("[brush-api] Brush API mod initialised (Phase C skeleton, docs/26 section 4 C2a) --"
                + " this mod will host the game-capability APIs; the loader core keeps only"
                + " loader duties (mod discovery / metadata / entrypoints / class loading /"
                + " mixin registration / injection + dispatch / self-test)");

        // 网络引擎实现装入核心门面（2026-10-15，docs/26 §7.7）：
        // 引擎（BrushChannels / BrushPayload / ReplyGuard / DispatchDedupe / 宿主桥）住在**本模组**，
        // 而对外入口 `org.abstruck.brush.api.BrushNetwork` 以**门面**形式留在核心 ——
        // 因为该包是核心模块拥有的包，宿主（FML）加载的代码找不到时不会回退到我们的加载器
        // （实机回归现场：NoClassDefFoundError: org/abstruck/brush/api/BrushNetwork）。
        // 门面另有惰性解析兜底，故本行与其它 mod 的 onInitialize 顺序无关。
        BrushNetwork.install(new BrushNetworkImpl());

        // 宿主事件总线接缝（2026-10-15，docs/26 §7.6 的"引擎搬家"）：
        // 引擎（BrushNetwork / BrushChannels / BrushPayload）与它的 S3 宿主适配器（NeoForgePayloadBridge）
        // 现在都住在**本模组**里，而 S3 的 mod 事件总线只有核心的 @Mod 构造器拿得到 ——
        // 于是核心把总线**当 Object 递出**，由这里接住并在 S3 下挂载载荷转注册。
        // 引用 NeoForge 类型这件事留在这里（用户口径：Brush API 可以引用宿主类）。
        // 注意：lambda 体只在**被调用**时才解析 NeoForgePayloadBridge ⇒ S1/S2 下该类永不加载。
        HostSeams.onModEventBus(bus -> org.abstruck.brush.api.impl.host.NeoForgePayloadBridge.attach(bus));
        // ===== 储能（流体 / 能量）宿主桥（2026-10-16）=====
        // S3：核心把宿主 mod 事件总线以 Object 递出，这里接住 → 挂 RegisterCapabilitiesEvent，
        //     把 BrushCapabilities 已登记的储能转登记给 NeoForge（Capabilities.Fluid.Block / Energy.Block）。
        //     引用 NeoForge 类型这件事留在桥类里：lambda 体只在**被调用**时才解析它，S1/S2 永不加载。
        HostSeams.onModEventBus(bus -> org.abstruck.brush.api.impl.host.NeoForgeCapabilityBridge.attach(bus));

        // S2：探测 Fabric Loader（只探测类名，不引用 Fabric 类型）；在 Fabric 下装载 Transfer API 桥。
        // S1（纯 Brush）/ S3（NeoForge）下这里什么都不做，宿主桥保持未装载。
        try {
            Class.forName("net.fabricmc.loader.api.FabricLoader");
            org.abstruck.brush.api.impl.host.FabricCapabilityBridge.install();
        } catch (ClassNotFoundException notFabricHost) {
            LOGGER.info("[brush-api] no Fabric host detected -- Brush capability registry only (S1/S3 path)");
        }
    }
}
