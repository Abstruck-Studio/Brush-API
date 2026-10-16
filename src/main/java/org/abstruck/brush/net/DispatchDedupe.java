package org.abstruck.brush.net;

/**
 * 同一"逻辑投递"的派发去重（docs/27 §2.7.3）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>实测事实（26.1.2，本地/集成服务器连接）：原版会把**同一个**
 * {@code ClientboundCustomPayloadPacket} <b>实例</b>处理两次 ——
 * 一次在 netty 线程内联（{@code Connection.channelRead0}），
 * 一次在主线程排队（{@code PacketProcessor.processQueuedPackets} ← {@code Minecraft.runTick}）。
 * 两次的 {@code System.identityHashCode} 完全相同。若不去重，使用方会**重复执行副作用**。</p>
 *
 * <h2>为什么不是"只记最后一个"（单槽）</h2>
 *
 * <p>单槽在**两个包交错**时会漏掉一个：设 X、Y 在一个 tick 内先后到达，</p>
 *
 * <pre>
 * X 内联 → 认领 X（槽=X）
 * Y 内联 → 认领 Y（槽=Y，X 被顶掉）
 * 主线程队列处理 X → 槽里是 Y ≠ X → **又认领一次** → X 被派发两次  ← 漏
 * </pre>
 *
 * <p>因此本类保留**最近 N 个** token 的<b>身份</b>（identity，不是 equals）构成的窗口。
 * N 取 {@link #DEFAULT_CAPACITY}：原版的"排队"延迟是到主线程处理队列为止（同一 tick 量级），
 * 窗口远大于该窗口期内可能出现的载荷数。</p>
 *
 * <h2>代价（显式取舍，不埋着）</h2>
 *
 * <p>强引用最近 N 个 token。在本用途下 token 是包对象（含其缓冲区），因此**有界内存占用
 * = N 个包**，不随运行时长增长。若将来用于高频/大对象场景，需重新评估 N 或改弱引用。</p>
 *
 * <h2>线程</h2>
 *
 * <p>两条处理路径（netty 线程 / 主线程）会并发调用，故 {@link #claim(Object)} 是
 * {@code synchronized} 的。本类在**冷路径**上使用（每个自定义载荷一次），不追求无锁。</p>
 */
public final class DispatchDedupe {

    /** 默认窗口容量（保留最近这么多个 token 的身份）。 */
    public static final int DEFAULT_CAPACITY = 64;

    /** 身份窗口（环形）；按占用顺序写入，满则覆盖最旧的。 */
    private final Object[] window;

    /** 下一个写入位置。 */
    private int next;

    /** 以默认窗口容量构造。 */
    public DispatchDedupe() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 以指定窗口容量构造。
     *
     * @param capacity 窗口容量（&gt; 0）
     * @throws IllegalArgumentException 容量非正
     */
    public DispatchDedupe(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.window = new Object[capacity];
    }

    /**
     * 认领一次派发权。
     *
     * @param token 本次投递的标识对象（本用途下 = 包实例）；不得为 {@code null}
     * @return {@code true} = 本线程获得派发权（应派发）；{@code false} = 该 token 已在窗口内（应跳过）
     * @throws NullPointerException token 为 null
     */
    public synchronized boolean claim(Object token) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        for (Object seen : window) {
            if (seen == token) {   // 身份比较：同一对象才算同一次投递
                return false;
            }
        }
        window[next] = token;
        next = (next + 1) % window.length;
        return true;
    }

    /** 窗口容量（测试与文档用）。 */
    public int capacity() {
        return window.length;
    }
}
