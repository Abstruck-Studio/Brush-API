package org.abstruck.brush.api.impl.host;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.abstruck.brush.net.BrushChannels;
import org.abstruck.brush.net.BrushPayload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * S3（NeoForge 宿主）载荷转注册桥 —— 把 {@code BrushNetwork} 已登记的通道转登记进 NeoForge 的载荷注册表。
 *
 * <h2>为什么必须有它（2026-10-15 实机实测的缺陷）</h2>
 *
 * <p>NeoForge **要求自定义载荷先经它的注册表登记**，未登记者在发送时被
 * {@code net.neoforged.neoforge.network.registration.NetworkRegistry#checkPacket} 拒绝：
 * {@code UnsupportedOperationException: Payload <id> may not be sent to the server!}。
 * 实机证据：S3 实机探针跑到发载荷时子 JVM 崩溃（
 * {@code run/neoforge/crash-reports/crash-2026-10-15_00.08.27-client.txt}）。
 * vanilla 侧没有这种校验（26.1.2 的 {@code CustomPacketPayload} 无方向接口，javap 实证），
 * 所以这是**宿主特有**的登记要求 —— S1/S2 不受影响。</p>
 *
 * <h2>为什么挂在这个事件上、且时机成立</h2>
 *
 * <p>{@code RegisterPayloadHandlersEvent} 是 mod bus 事件，由 {@code NetworkRegistry.setup()}
 * **一次性**触发（重复 setup 抛异常）。我们引导 mod 的时机是 {@code Bootstrap.bootStrap} HEAD ——
 * 在各 mod 的 {@code onInitialize()} 里就已调用 {@code BrushNetwork.register(...)}，
 * 即**事件到来时通道表已装满**，可以一次性全部转登记。（事件精确触发点位于 FML loader jar，
 * 本注解不声称已字节码直证；若推断有误则事件到来时通道表为空、什么都注册不上，
 * S3 实机探针会照旧崩 —— 不会假绿。）</p>
 *
 * <h2>为什么 handler 是**空实现**（本轮的选择，附判据）</h2>
 *
 * <p>登记的**唯一目的**是让 {@code checkPacket} 放行；**投递**仍走我们自己的注入点
 * （{@code brush-api} 对客户端 / 服务端 {@code handleCustomPayload} 的 HEAD 注入），
 * 与 S1/S2 完全同一条链。若这里再转发一次，就会出现**双重投递**。</p>
 *
 * <p>但存在另一种可能：NeoForge 的补丁若在某处**截走**了载荷、使我们的注入点根本不触发，
 * 那么空 handler 会导致**零投递**。两种情形都由 S3 实机探针的硬断言区分 ——
 * 它断言 {@code server.pings=1, client.pongs=1}（多一次 / 少一次都会打红）。
 * 若实测为"零投递"，把 handler 改为**转发进我们自己的派发路径**即可（
 * {@code Dispatcher.fire(HookEvents.SERVER_PAYLOAD / NETWORK_PAYLOAD, ...)}）。</p>
 *
 * <h2>幂等与迟到登记</h2>
 *
 * <p>① {@link #attach} 幂等（只挂一次监听）；② 每个 id 只登记一次（NeoForge 对同 id 二次登记抛异常）；
 * ③ <b>事件之后才登记的通道不会被桥接</b> —— 这类通道在 S3 下仍不可用，
 * 故本类在登记时把"当时已知的通道清单"打进日志，便于对照排查（mod 作者应在初始化期登记通道）。</p>
 *
 * <p><b>归属（2026-10-15 变更）</b>：本类随网络引擎一起从核心搬到**库模组** {@code brush-api}
 * （{@code org.abstruck.brush.api.impl.host}），因为引擎已归库模组、而核心不得反向依赖 mod
 * （CLAUDE.md §18）。核心改为经中性接缝 {@code org.abstruck.brush.host.HostSeams} 把宿主事件总线
 * **当 Object 递出**；"引用 NeoForge 类型"因此落在允许引用宿主类的库模组里
 * （用户 2026-10-15 口径：Brush API 可以引用宿主类 —— 平台特定 mixin + {@code @BrushExpectPlatform}）。
 * 全案见 docs/26 §7.6 的更新块。</p>
 */
public final class NeoForgePayloadBridge {

    /** 与核心同类口径的日志名（落 run/neoforge/logs/latest.log）。 */
    private static final Logger LOGGER = LogManager.getLogger("Brush");

    /** attach 幂等（宿主可能重复构造，或兜底路径再次调用）。 */
    private static final AtomicBoolean ATTACHED = new AtomicBoolean();

    /** 已桥接的通道 id（NeoForge 对同 id 二次登记抛异常 ⇒ 必须自己守）。 */
    private static final Set<Identifier> BRIDGED = ConcurrentHashMap.newKeySet();

    /** 载荷注册事件是否已经发生（守卫据此判断"未桥接"是否**真的**不可发送）。 */
    private static volatile boolean BRIDGE_DONE;

    /**
     * 空载荷处理器（见类 javadoc「为什么 handler 是空实现」）。
     * 同一个实例可同时用于两个方向 —— 它不做任何事、无状态。
     */
    private static final IPayloadHandler<BrushPayload> NOOP = (payload, context) -> {
    };

    private NeoForgePayloadBridge() {
    }

    /**
     * 挂载监听（在 {@code @Mod} 构造器内调用一次；mod bus 监听必须在事件派发前挂上）。
     *
     * @param modEventBus 宿主注入的 mod 事件总线
     */
    /**
     * 接缝入口（2026-10-15 搬家后）：由库模组入口经
     * {@code org.abstruck.brush.host.HostSeams.onModEventBus(...)} 注册的回调调用 ——
     * 宿主事件总线以 {@code Object} 递入，于是**核心零 mod 类型引用**，
     * 而"引用 NeoForge 类型"这件事留在这个允许引用宿主类的库模组里（docs/26 §7.6）。
     *
     * @param modEventBus 宿主的 mod 事件总线（S3 下必为 {@code IEventBus}）
     * @throws IllegalStateException 递进来的不是 {@code IEventBus}（接缝用错宿主）
     */
    public static void attach(Object modEventBus) {
        if (!(modEventBus instanceof IEventBus bus)) {
            throw new IllegalStateException("[brush-api] NeoForge payload bridge received a non-IEventBus: "
                    + (modEventBus == null ? "null" : modEventBus.getClass().getName()));
        }
        if (!ATTACHED.compareAndSet(false, true)) {
            return;
        }
        bus.addListener(NeoForgePayloadBridge::onRegisterPayloadHandlers);
        // 安装"可发送性"守卫（核心钩子，见 BrushChannels.SendGuard）：
        // 桥接机会只有一次 ⇒ 事件之后才登记的通道在 S3 下发不出去。这里把它变成
        // **Brush 自己的可读硬失败**（含补救指引），而不是 NeoForge 的 UOE 或静默失效。
        BrushChannels.setSendGuard(channel -> {
            if (!BRIDGE_DONE || BRIDGED.contains(channel)) {
                return null;   // 事件尚未发生，或该通道已桥接 ⇒ 放行
            }
            return "brush channel " + channel + " cannot be sent under NeoForge (S3): it was registered"
                    + " AFTER the NeoForge payload-registration event, so it is not in NeoForge's payload"
                    + " registry and its checkPacket would reject the packet. Register channels from the"
                    + " mod's MAIN entrypoint (during initialization), not from a client entrypoint"
                    + " -- see docs/29 section 6.5";
        });
        LOGGER.info("[brush] S3 payload bridge attached -- waiting for RegisterPayloadHandlersEvent"
                + " (send guard installed)");
    }

    /** 事件处理：把当时已知的每个 Brush 通道登记为双向载荷。 */
    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");   // 版本串：两侧同代码 ⇒ 同为 "1"，协商一致
        Set<Identifier> known = BrushChannels.registeredIds();
        int registered = 0;
        for (Identifier id : known) {
            if (!BRIDGED.add(id)) {
                continue;   // 幂等：NeoForge 对同 id 二次登记会抛异常
            }
            int maxSize = BrushChannels.maxSizeOf(id);
            StreamCodec<FriendlyByteBuf, BrushPayload> codec = BrushPayload.codec(id, maxSize);
            CustomPacketPayload.Type<BrushPayload> type = new CustomPacketPayload.Type<>(id);
            // 方向语义（javap 实证 register(type, codec, serverboundHandler, clientboundHandler, ...)：
            // 槽位 3 = 服务端 handler、槽位 4 = 客户端 handler；playToServer 正是把槽位 4 置 null）。
            // 用 playBidirectional + Optional.empty() flow：同一 id 两个方向都登记（我们的 Reply 是 clientbound）。
            registrar.playBidirectional(type, codec, NOOP, NOOP);
            registered++;
            LOGGER.info("[brush] S3 payload bridge: registered channel " + id + " (maxSize=" + maxSize + ")");
        }
        LOGGER.info("[brush] S3 payload bridge: done -- registered " + registered + " of " + known.size()
                + " known channel(s): " + known);
        BRIDGE_DONE = true;
        // 事件之后仍可能登记通道（如 mod 在客户端入口点登记）—— 那些通道无法再桥接，
        // 由 attach() 安装的发送守卫在真正尝试发送时给出可读失败。
    }

    /** 某通道是否已被桥接（诊断用；不改变语义）。 */
    public static boolean isBridged(Identifier id) {
        return BRIDGED.contains(id);
    }
}
