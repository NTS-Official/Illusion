package dev.nts.expdxr.mixin.debug;

import dev.nts.expdxr.d3d12.render.core.D3D12GpuSurface;
import dev.nts.expdxr.utils.IllusionLogger;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injected at Minecraft.runTick() HEAD to log level / gameLoadFinished state
 * each frame, so we can see when (or if) they ever become non-null/true.
 *
 * P50：默认关闭。此前每帧无条件执行 5 次反射 getDeclaredField + stderr println/flush，
 * 单会话产生 ~1.7 万行日志，是渲染线程最大的 CPU 开销之一。改为仅 DX12_LOG_VERBOSE=1 时启用。
 *
 * P101（2026-10-14 追加）：环境变量 DXRMC_OPEN_INVENTORY=<秒> 时，进入世界 N 秒后
 * 自动打开物品栏。目的：物品栏渲染 bug（多数物品空白 / 少数半截 / 玩家模型上下颠倒）
 * 需要稳定复现，而 SendInput 发 E 依赖窗口焦点、不可靠。这里在 runTick（渲染线程）里
 * 直接 setScreen，100% 确定性，并在日志里留一行可核对。
 */
@Mixin(Minecraft.class)
public class MinecraftRunTickDebugMixin {

    /** 0 = 关闭；>0 = 进入世界后这么多秒自动开物品栏。 */
    @Unique
    private static final int illusion$D3D12InvDelaySec = illusion$D3D12EnvInt();
    /**
     * P101c：每帧反射 + 一行日志的 runTick 调试输出，改为**显式**开关（DXRMC_RUN_TICK_DEBUG=1），
     * 不再跟随 DX12_LOG_VERBOSE。理由：整轮 verbose 会喷 60 MB 日志、把 CPU 吃满，
     * 而冒烟复现（进世界/开物品栏）只需要 mixin 的两行自报。
     */
    @Unique
    private static final boolean illusion$D3D12RunTickDebug = "1".equals(System.getenv("DXRMC_RUN_TICK_DEBUG"));
    @Unique
    private static long illusion$D3D12LevelSeenAtMs = -1L;
    @Unique
    private static boolean illusion$D3D12InvOpened = false;

    @Unique
    private static int illusion$D3D12EnvInt() {
        try {
            String v = System.getenv("DXRMC_OPEN_INVENTORY");
            if (v == null || v.isEmpty()) {
                return 0;
            }
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Inject(method = "runTick", at = @At("HEAD"), remap = false)
    private void dx12_runTickDebug(boolean advanceGameTime, CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        // P101c：把"进世界后自动开物品栏"放在 verbose 门之前 —— 它由 DXRMC_OPEN_INVENTORY
        // 自己控制，复现物品栏 bug 时**不需要**开 verbose（verbose 每次运行喷 60 MB 日志，
        // 既费 CPU 又把取证日志淹没）。此前它被 !debugEnabled() 挡住，静默模式下根本不会触发。
        if (illusion$D3D12InvDelaySec > 0 && !illusion$D3D12InvOpened) {
            Object lvl = null;
            try {
                java.lang.reflect.Field lf = Minecraft.class.getDeclaredField("level");
                lf.setAccessible(true);
                lvl = lf.get(mc);
            } catch (Exception ignored) {}
            illusion$D3D12MaybeOpenInventory(mc, lvl);
        }
        if (!illusion$D3D12RunTickDebug) {
            return;
        }
        java.lang.reflect.Field levelField, tickCountField, loadField, pendingField, serverField;
        Object level = null;
        long tickCount = 0L;
        boolean gameLoadFinished = false;
        Object pendingConnection = null;
        Object singleplayerServer = null;
        try {
            levelField = Minecraft.class.getDeclaredField("level");
            levelField.setAccessible(true);
            level = levelField.get(mc);
        } catch (Exception ignored) {}
        try {
            tickCountField = Minecraft.class.getDeclaredField("clientTickCount");
            tickCountField.setAccessible(true);
            tickCount = tickCountField.getLong(mc);
        } catch (Exception ignored) {}
        try {
            loadField = Minecraft.class.getDeclaredField("gameLoadFinished");
            loadField.setAccessible(true);
            gameLoadFinished = loadField.getBoolean(mc);
        } catch (Exception ignored) {}
        try {
            pendingField = Minecraft.class.getDeclaredField("pendingConnection");
            pendingField.setAccessible(true);
            pendingConnection = pendingField.get(mc);
        } catch (Exception ignored) {}
        try {
            serverField = Minecraft.class.getDeclaredField("singleplayerServer");
            serverField.setAccessible(true);
            singleplayerServer = serverField.get(mc);
        } catch (Exception ignored) {}
        String levelStr = (level != null) ? "non-null" : "NULL";
        IllusionLogger.debugMessage("[backend-debug] runTick tick=" + tickCount
            + " gameLoadFinished=" + gameLoadFinished
            + " level=" + levelStr
            + " pause=" + mc.isPaused()
            + " pendingConn=" + (pendingConnection != null ? "present" : "null")
            + " singleplayerServer=" + (singleplayerServer != null ? "present" : "null"));
        System.err.flush();
    }

    /**
     * 全部走反射：26.2 起「当前界面」挂在 `Minecraft.gui`（net.minecraft.client.gui.Gui）上
     * （`Gui.screen` 字段、`public @Nullable Screen screen()`、`public void setScreen(Screen)`），
     * 而 `Minecraft.screen` / `Minecraft.setScreen(Screen)` 只存在于旧映射。
     * 两代名字都探——只认旧名字的写法在本版本里会静默读成 null，等于悄悄放弃了
     * 「有别的界面就先不动」的保护；这里的候选名探测 + 命中名回日志让改名不会静默失效。
     */
    @Unique
    private void illusion$D3D12MaybeOpenInventory(Minecraft mc, Object level) {
        try {
            if (level == null || mc.player == null) {
                illusion$D3D12LevelSeenAtMs = -1L;
                return;
            }
            long now = System.currentTimeMillis();
            if (illusion$D3D12LevelSeenAtMs < 0L) {
                illusion$D3D12LevelSeenAtMs = now;
                // 每轮运行只打 1 行 ⇒ 直接走 stderr，不依赖 verbose 开关。
                System.err.println("[backend-debug] DXRMC_OPEN_INVENTORY: 世界已进入，"
                    + illusion$D3D12InvDelaySec + " 秒后自动打开物品栏");
                System.err.flush();
                return;
            }
            if (now - illusion$D3D12LevelSeenAtMs < illusion$D3D12InvDelaySec * 1000L) {
                return;
            }
            // 还有别的界面（加载遮罩 / 暂停菜单）时先不动，等它自己关掉。
            Object cur = illusion$D3D12CurrentScreen(mc);
            if (cur != null) {
                return;
            }
            illusion$D3D12InvOpened = true;
            Object inv = new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player);
            String how = illusion$dx12CallSetScreen(mc, inv);
            System.err.println("[backend-debug] DXRMC_OPEN_INVENTORY: InventoryScreen 已打开（"
                + (now - illusion$D3D12LevelSeenAtMs) + " ms, via " + how + "）");
            System.err.flush();
            // P101d：紧接着的 3 帧各落盘一张主画面 BMP（只在 MCDR_DUMP_TEX=1 时生效）——
            // 这样取证图正好是"物品栏已打开"的那几帧，不需要按帧号碰运气。
            try {
                D3D12GpuSurface.requestScreenDumps(3, "inv");
            } catch (Throwable ignored) {
                // 取证钩子绝不影响游戏
            }
        } catch (Throwable t) {
            illusion$D3D12InvOpened = true;
            System.err.println("[backend-debug] DXRMC_OPEN_INVENTORY 失败: " + t);
            System.err.flush();
        }
    }

    /** 读 Minecraft 自己的字段（旧名字走这里）。 */
    @Unique
    private static Object illusion$dx12ReadField(Object target, String name) {
        return illusion$D3D12ReadFieldOf(target, name, Minecraft.class);
    }

    /** 按候选名字读任意对象的字段；名字不存在返回 null（调用方自己决定要不要再试别的名字）。 */
    @Unique
    private static Object illusion$D3D12ReadFieldOf(Object target, String name, Class<?> on) {
        try {
            java.lang.reflect.Field f = on.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 无参方法调用；失败返回 null。 */
    @Unique
    private static Object illusion$D3D12CallNoArg(Object target) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod("screen");
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 当前打开的界面：先问 26.2 的 `Minecraft.gui`（`Gui.screen()` / `Gui.screen` 字段），
     * 再退回旧映射的 `Minecraft.screen` / `Minecraft.currentScreen`。全都不命中时返回 null
     * （语义 = 「没有别的界面」），但那条路径只会在又一次改名后走到。
     */
    @Unique
    private static Object illusion$D3D12CurrentScreen(Minecraft mc) {
        Object gui = illusion$dx12ReadField(mc, "gui");
        if (gui != null) {
            Object s = illusion$D3D12CallNoArg(gui);
            if (s == null) {
                s = illusion$D3D12ReadFieldOf(gui, "screen", gui.getClass());
            }
            if (s != null) {
                return s;
            }
        }
        Object legacy = illusion$dx12ReadField(mc, "screen");
        if (legacy == null) {
            legacy = illusion$dx12ReadField(mc, "currentScreen");
        }
        return legacy;
    }

    /** 26.2 的 `Gui.setScreen(Screen)`；退回旧版 `Minecraft.setScreen` / `setScreenAndShow`，再退回任意单 Screen 参数的方法。 */
    private static String illusion$dx12CallSetScreen(Minecraft mc, Object screen) {
        Object gui = illusion$dx12ReadField(mc, "gui");
        if (gui != null) {
            try {
                java.lang.reflect.Method m = gui.getClass().getMethod("setScreen",
                    net.minecraft.client.gui.screens.Screen.class);
                m.invoke(gui, screen);
                return "gui.setScreen";
            } catch (Throwable ignored) {
                // 退回旧名字
            }
        }
        for (String name : new String[] {"setScreen", "setScreenAndShow"}) {
            try {
                java.lang.reflect.Method m = Minecraft.class.getMethod(name,
                    net.minecraft.client.gui.screens.Screen.class);
                m.invoke(mc, screen);
                return name;
            } catch (Throwable ignored) {
                // 试下一个名字
            }
        }
        for (java.lang.reflect.Method m : Minecraft.class.getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 1 && ps[0].isAssignableFrom(screen.getClass())) {
                try {
                    m.invoke(mc, screen);
                    return m.getName();
                } catch (Throwable ignored) {
                    // 继续找
                }
            }
        }
        throw new IllegalStateException("no setScreen-like method found on Minecraft");
    }
}

