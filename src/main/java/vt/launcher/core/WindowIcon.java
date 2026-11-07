package vt.launcher.core;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Gives the Minecraft window the launcher's icon (title bar and taskbar), for every version and every loader.
 * Minecraft sets its own icon, so a tiny PowerShell helper waits for the game window (found by the game's process id)
 * and sends it the launcher icon. The same helper tells the launcher the moment the window appears, which is what
 * lets the launcher keep its loading screen until the game is really there.
 */
public final class WindowIcon {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private WindowIcon() {}

    /** vt-launcher/logo.ico, built from the launcher logo (vt/logo.png inside the app). null if there is no logo. */
    static Path ensureIco(Config cfg) {
        try {
            Path ico = cfg.vtDir().resolve("window-icon.ico");
            try (InputStream in = WindowIcon.class.getResourceAsStream("/vt/logo.png")) {
                if (in == null) {
                    // no logo.png in the build: use a ready-made vt.ico if there is one
                    try (InputStream i2 = WindowIcon.class.getResourceAsStream("/vt/vt.ico")) {
                        if (i2 == null) return null;
                        Files.createDirectories(ico.getParent());
                        Files.write(ico, i2.readAllBytes());
                        return ico;
                    }
                }
                BufferedImage logo = ImageIO.read(in);
                if (logo == null) return null;
                Files.createDirectories(ico.getParent());
                Files.write(ico, buildIco(logo));
            }
            return ico;
        } catch (Exception e) {
            Log.warn("Could not prepare the window icon: " + e.getMessage());
            return null;
        }
    }

    /** An .ico with several sizes, each stored as PNG (supported by every Windows since Vista). */
    static byte[] buildIco(BufferedImage logo) throws IOException {
        int n = SIZES.length;
        byte[][] pngs = new byte[n][];
        for (int i = 0; i < n; i++) {
            int s = SIZES[i];
            BufferedImage sq = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            var g = sq.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min((double) s / logo.getWidth(), (double) s / logo.getHeight());
            int w = Math.max(1, (int) Math.round(logo.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(logo.getHeight() * scale));
            g.drawImage(logo, (s - w) / 2, (s - h) / 2, w, h, null);
            g.dispose();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(sq, "png", b);
            pngs[i] = b.toByteArray();
        }
        ByteBuffer buf = ByteBuffer.allocate(6 + 16 * n + java.util.Arrays.stream(pngs).mapToInt(a -> a.length).sum())
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) n);
        int offset = 6 + 16 * n;
        for (int i = 0; i < n; i++) {
            int s = SIZES[i];
            buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(pngs[i].length).putInt(offset);
            offset += pngs[i].length;
        }
        for (byte[] p : pngs) buf.put(p);
        return buf.array();
    }

    private static final String SCRIPT = String.join("\r\n",
            "param([int]$GamePid, [string]$Ico, [int]$MaxSeconds = 600)",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "Add-Type -TypeDefinition @\"",
            "using System;",
            "using System.Runtime.InteropServices;",
            "public static class VtWin {",
            "  public delegate bool EnumProc(IntPtr h, IntPtr l);",
            "  [DllImport(\"user32.dll\")] public static extern bool EnumWindows(EnumProc p, IntPtr l);",
            "  [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
            "  [DllImport(\"user32.dll\")] public static extern bool IsWindowVisible(IntPtr h);",
            "  [DllImport(\"user32.dll\")] public static extern int GetWindowTextLength(IntPtr h);",
            "  [DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern IntPtr LoadImage(IntPtr i, string n, uint t, int cx, int cy, uint f);",
            "  [DllImport(\"user32.dll\")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);",
            "  public static IntPtr Find(uint pid) {",
            "    IntPtr found = IntPtr.Zero;",
            "    EnumWindows(delegate(IntPtr h, IntPtr l) {",
            "      uint p; GetWindowThreadProcessId(h, out p);",
            "      if (p == pid && IsWindowVisible(h) && GetWindowTextLength(h) > 0) { found = h; return false; }",
            "      return true;",
            "    }, IntPtr.Zero);",
            "    return found;",
            "  }",
            "}",
            "\"@",
            "$deadline = (Get-Date).AddSeconds($MaxSeconds)",
            "$hwnd = [IntPtr]::Zero",
            "while ((Get-Date) -lt $deadline) {",
            "  if (-not (Get-Process -Id $GamePid)) { exit 0 }",
            "  $hwnd = [VtWin]::Find([uint32]$GamePid)",
            "  if ($hwnd -ne [IntPtr]::Zero) { break }",
            "  Start-Sleep -Milliseconds 100",
            "}",
            "if ($hwnd -eq [IntPtr]::Zero) { exit 0 }",
            "[Console]::Out.WriteLine('WINDOW')",
            "[Console]::Out.Flush()",
            "if ($Ico -and (Test-Path $Ico)) {",
            "  $big = [VtWin]::LoadImage([IntPtr]::Zero, $Ico, 1, 64, 64, 0x10)",
            "  $small = [VtWin]::LoadImage([IntPtr]::Zero, $Ico, 1, 16, 16, 0x10)",
            "  # the game may set its own icon a moment after the window appears, so set ours a few times",
            "  for ($i = 0; $i -lt 6; $i++) {",
            "    if ($big -ne [IntPtr]::Zero) { [void][VtWin]::SendMessage($hwnd, 0x80, [IntPtr]1, $big) }",
            "    if ($small -ne [IntPtr]::Zero) { [void][VtWin]::SendMessage($hwnd, 0x80, [IntPtr]0, $small) }",
            "    Start-Sleep -Milliseconds 700",
            "    $hwnd = [VtWin]::Find([uint32]$GamePid)",
            "    if ($hwnd -eq [IntPtr]::Zero) { break }",
            "  }",
            "}",
            "");

    /**
     * Starts the helper for a running game process. onWindow is called (from another thread) when the game window shows up.
     * Does nothing (and never throws) when PowerShell is not available.
     */
    public static void watch(Config cfg, Process game, Runnable onWindow) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Thread.ofPlatform().daemon().name("vt-window-icon").start(() -> {
            try {
                Path ico = ensureIco(cfg);
                Path script = cfg.vtDir().resolve("window-helper.ps1");
                Files.createDirectories(script.getParent());
                // Windows PowerShell 5 reads a script without BOM as ANSI; the script is plain ASCII on purpose
                Files.writeString(script, SCRIPT);
                Process ps = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                        "-File", script.toString(),
                        "-GamePid", String.valueOf(game.pid()),
                        "-Ico", ico == null ? "" : ico.toString())
                        .redirectErrorStream(true).start();
                game.onExit().thenRun(() -> {
                    try {
                        if (ps.isAlive() && !ps.waitFor(3, TimeUnit.SECONDS)) ps.destroyForcibly();
                    } catch (InterruptedException ignored) {
                    }
                });
                try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(ps.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.trim().equals("WINDOW")) onWindow.run();
                    }
                }
            } catch (Exception e) {
                Log.warn("Window helper not available: " + e.getMessage());
            }
        });
    }
}
