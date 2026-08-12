# -*- coding: utf-8 -*-
"""截取 Minecraft 游戏窗口截图(Windows)。
用法: python tools/shot_minecraft.py <输出png路径>
"""
import subprocess
import sys
import os

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ["TEMP"], "mc_shot.png")
OUT_PS = OUT.replace("\\", "\\\\")

PS = r'''
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class W32 {
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lp);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder sb, int max);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    public delegate bool EnumProc(IntPtr h, IntPtr lp);
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
$found = [IntPtr]::Zero
$cb = [W32+EnumProc]{
    param($h, $lp)
    $t = New-Object System.Text.StringBuilder 256
    [W32]::GetWindowText($h, $t, 256) | Out-Null
    if ([W32]::IsWindowVisible($h) -and ($t.ToString() -match "Minecraft" -or $t.ToString() -match "1\.20|新的世界")) {
        $script:found = $h
        return $false
    }
    return $true
}
[W32]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
if ($script:found -ne [IntPtr]::Zero) {
    [W32]::SetForegroundWindow($script:found) | Out-Null
    Start-Sleep -Milliseconds 500
    $r = New-Object W32+RECT
    [W32]::GetWindowRect($script:found, [ref]$r) | Out-Null
    $bounds = New-Object System.Drawing.Rectangle($r.Left, $r.Top, $r.Right - $r.Left, $r.Bottom - $r.Top)
    Write-Output ("window: " + $r.Left + "," + $r.Top + " " + $bounds.Width + "x" + $bounds.Height)
} else {
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    Write-Output "no window, full screen"
}
$bmp = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, $bounds.Size)
$bmp.Save('__OUT__', [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output "saved __OUT__"
'''.replace("__OUT__", OUT_PS)

r = subprocess.run(["powershell", "-NoProfile", "-Command", PS], capture_output=True, text=True, timeout=30)
print(r.stdout)
if r.stderr:
    print("ERR:", r.stderr[:500])
print("OK" if os.path.exists(OUT) else "FAILED", OUT, os.path.getsize(OUT) if os.path.exists(OUT) else 0)
