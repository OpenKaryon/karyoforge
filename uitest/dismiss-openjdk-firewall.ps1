# Loaded by the isolated VM runner. Interactive input is limited to a matching Cancel button.
function Initialize-KaryoForgeOpenJdkPrompt {
    if ('KaryoForgeOpenJdkPrompt' -as [type]) { return }
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -ReferencedAssemblies 'System.dll','System.Drawing.dll','System.Windows.Forms.dll' -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

public static class KaryoForgeOpenJdkPrompt {
    // Samples of glyph interiors and surrounding background for the Russian Windows 11 dialog.
    // Separate masks cover the Windows Security title, OpenJDK product name, Cancel label, and JetBrains publisher.
    private static readonly byte[][] OpenJdk = {
        Convert.FromBase64String("Wv84/2//NP9g/zb/Qv85/5P/N/9t/zj/pP84/6b/NP+C/zf/Pf81/3T/OP+x/zX/nf81/zr/OP+a/zb/aP82/3b/NP9K/zj/of81/0H/Nf91/zn/YP83/7b/NP+a/zX/k/85/4j/Nf+0/zn/Xv83/5P/Mf97/zT/Vv80/2b/Of9B/zb/lv80/2H/Of9c/zT/Zv8w/0H/L/85/zH/vf8u/8j/Nv+6/zP/bf8t/6X/Nf9m/zP/hv8u/83/NP+n/y7/yv86/7X/Lf/H/zn/Tf8u/7j/Ov9M/zL/Sv8u/3T/O/9s/y7/Xv87/w=="),
        Convert.FromBase64String("wv///ysABADA//7/if///xMAAADs//7/uP8GAFYACACW//7/f/8GADsA//+O/wAA4v8HAJ3/AgCs//z/5P/+/7D///93//z/5f///4r/BgDn/wEAlv///6T/AQDW/wEA/v/+/5X//v/B/wAARwAFAND/AwD0/wYAKwAFAEUA//99//v/AQAAAEsABgDi/wMADgAOAH4A/f/W//f/UQALAEEACQAjAAEAx/8OAGEACQBvAAoAFwABABMA9/+u//r/fgAOAFMABwAzAPf/jf/1/yIABQAIAPv/rf/+/93/9f8zAPr/cQAIAA=="),
        Convert.FromBase64String("RgCrAHUArgB0ALMAQgCsAFsAsABSAK8ARQCrAHwAtABXAK8AZACxAHkAsQBcALEAbwCuAEYAtAB6ALQATQCxAE0ArwBNALIAfACxAFsAsgBNAK4AawCzAHQAtABCALMAVACzAE0AswB7ALQAWAC0AHQArwBJAK8ASQCwAE0AtABYALIATACuAEgAsgBdAK4ATwCyAIEAswBcALAAggCuAIIAqgBsAKYARQCpAFQAqgA5AK4AUgC2AGEAsgA+AKcATgCsADgAqgBPALUAcwCqADwArgA8ALIAawCqAHEAqgB+ALEAgwCxAA=="),
        Convert.FromBase64String("fv8rAJ3/KACw/yUAsv8rAJ3/KQB5/ycAtf8oAH3/KwDD/ysAuf8rAMP/JQCX/yUAkv8kAHH/KACf/ysAy/8lALD/KADb/ykAl/8oAJr/KAB4/yoAsv8pAHz/JwCm/yQA2/8nALX/IgDg/yYAeP8oAKn/KQDN/yUAy/8rALz/JQCk/yYAnf8qAKT/KwC1/ycAuf8iAN7/LQCm/ysA2v8kAKH/HgDH/yAAk/8mALv/KACJ/yYAu/8kAIj/KgC//yIAhP8sAOf/KQB5/x8Amv8fAPb/JAD1/yUA1v8mAPz/LQDP/yUAn/8kAA==")
    };
    private static readonly byte[][] KaryoForge = {
        Convert.FromBase64String("sP89/4H/P/9F/z//tv8//5v/Pf9w/z3/e/89/2v/P/+N/z3/PP89/2L/QP93/0L/kv88/0//Qv9s/0H/lf8//5r/Pf94/z3/oP9C/5X/Pv8+/z7/nP8//5j/QP9f/z//lf89/z7/Qv+t/z//pv8+/2z/Qv+j/z3/YP9A/5z/Pv+Y/z//qP89/4r/PP9X/z3/QP9D/3v/Q/9Z/0X/0/86/1P/Rf+6/0P/x/83/8r/O/+j/zj/bf9H/2X/O//P/z3/iP9H/0X/Q//G/zz/RP9D/0X/QP+6/z//ff88/4b/RP+C/0b/gf9H/w=="),
        Convert.FromBase64String("qP/3/3z/+f9+//v/ov/9/4X/+/+Z/wAAnf/5/6P/9v/E//f/mf/9/4D//f+K//r/hv/+/5n/+v+Y//j/0f/7/6n/+v/S//r/j//5/3r/9//R//f/3f/2/6r/+f+f//X/y//4/7n/9v/Y//3/2v/+/9n//f9///L/mv/+/5f/9/+9//b/ef/8/7//9/+0//f/oP/7/+3/AgCM/+7/1P/u/5//AACl/wEAu//5/8n/9v/l//v/dv/0/7f//v/q////l//x/5P/AADA/wEAwf/+/8z/8f/I//n/vf/5/7//7f+e/wIAdf/3/w=="),
        Convert.FromBase64String("TACnAG0ApwBKAKUAYwCoAHMApgBuAKYAWwCrAFcAqABuAKUAcgClAEEAowBDAKIAbACoAFcApwBVAKgAVwCpAHsAqwBMAKgARQCiAFoAqABEAKIASwClAE4ApQB6AKgAQAClAFcApgBHAKkAQgCiAFYApgBlAKgASACmAHMAqABxAKUARwCkAGQAqABCAKsAawChAGgAngBdALMARACdAG8AnAB4AKEASwC1ADwAmwBMAKEAQwC0AGcAmgBKALIAVgChAG0AngBpAK8AOwCkAHQArQA5AJwAZQCzAGwArQB/ALIAaQC0AA=="),
        Convert.FromBase64String("qf8iAM//HQCm/xwAsf8iAKz/HgDc/x0A0P8cANz/IgC//yIAsf8eAJr/HwC+/xwAuf8hANf/HgDR/yIAnf8jAJ3/HgC0/xoAsf8dAKz/IQDS/yIAyP8eANf/HACY/xoApv8iANT/IQCa/x4Atv8eANf/IACm/x8Akv8eAMj/HQCs/x8Auf8ZAJL/HQDA/yIA0P8fAM3/JQCf/xoApf8UAOj/IACh/xoAqP8WAOH/GwCl/yUA3v8YALb/FwDk/xYA5v8mAMT/GACQ/yUA4P8gAJv/FgCh/xsAkP8iAOn/IACz/xoAlP8eAA==")
    };
    private static readonly Prompt[] Prompts = {
        new Prompt("OpenJDK", OpenJdk, new Point(95, 176)),
        new Prompt("KaryoForge", KaryoForge, new Point(94, 167))
    };

    private sealed class Prompt {
        public readonly string Product;
        public readonly byte[][] Samples;
        public readonly Point Cancel;
        public Prompt(string product, byte[][] samples, Point cancel) {
            Product = product;
            Samples = samples;
            Cancel = cancel;
        }
    }

    private static IntPtr lastWindow;
    private static Point lastTarget;
    private static bool reportedUnavailableTarget;

    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern IntPtr WindowFromPoint(ScreenPoint point);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr window, uint flags);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("kernel32.dll")] private static extern IntPtr OpenProcess(uint access, bool inherit, uint process);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)]
    private static extern bool QueryFullProcessImageName(IntPtr process, uint flags, StringBuilder name, ref int length);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out ScreenPoint point);
    [DllImport("user32.dll")] private static extern uint SendInput(uint count, Input[] inputs, int size);
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll", SetLastError=true)] private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder value, int bytes, out int needed);
    [DllImport("user32.dll")] private static extern bool CloseDesktop(IntPtr handle);
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr window);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr window, IntPtr dc);
    [DllImport("gdi32.dll")] private static extern bool BitBlt(IntPtr dest, int x, int y, int width, int height,
        IntPtr source, int sourceX, int sourceY, uint rasterOperation);

    [StructLayout(LayoutKind.Sequential)]
    private struct ScreenPoint {
        public int X, Y;
        public ScreenPoint(Point point) { X = point.X; Y = point.Y; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput {
        public int X, Y;
        public uint Data, Flags, Time;
        public UIntPtr Extra;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input {
        public uint Type;
        public MouseInput Mouse;
    }

    private static bool DefaultDesktop() {
        IntPtr desktop = OpenInputDesktop(0, false, 1);
        if (desktop == IntPtr.Zero) return false;
        try {
            var name = new StringBuilder(128);
            int needed;
            return GetUserObjectInformation(desktop, 2, name, name.Capacity * 2, out needed)
                && String.Equals(name.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
        } finally { CloseDesktop(desktop); }
    }

    private static bool WindowsDialog(IntPtr window) {
        uint pid;
        GetWindowThreadProcessId(window, out pid);
        IntPtr process = OpenProcess(0x1000, false, pid); // PROCESS_QUERY_LIMITED_INFORMATION
        if (process == IntPtr.Zero) return false;
        try {
            var image = new StringBuilder(4096);
            int length = image.Capacity;
            if (!QueryFullProcessImageName(process, 0, image, ref length)) return false;
            string path = image.ToString();
            string windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            string apps = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            return path.StartsWith(windows + "\\System32\\", StringComparison.OrdinalIgnoreCase)
                || path.StartsWith(windows + "\\SystemApps\\", StringComparison.OrdinalIgnoreCase)
                || path.StartsWith(apps + "\\WindowsApps\\Microsoft.SecHealthUI_", StringComparison.OrdinalIgnoreCase);
        } finally { CloseHandle(process); }
    }

    public static Point? Match(Bitmap image, int centerX, int centerY) {
        string product;
        return Match(image, centerX, centerY, out product);
    }

    private static Point? Match(Bitmap image, int centerX, int centerY, out string product) {
        product = null;
        if (centerX < 231 || centerX + 165 >= image.Width || centerY < 241 || centerY + 216 >= image.Height) return null;
        var area = new Rectangle(0, 0, image.Width, image.Height);
        var bitmap = image.LockBits(area, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try {
            if (bitmap.Stride <= 0) return null;
            var pixels = new byte[bitmap.Stride * bitmap.Height];
            Marshal.Copy(bitmap.Scan0, pixels, 0, pixels.Length);
            int best = 29;
            Point? target = null;
            bool ambiguous = false;
            for (int y = centerY - 24; y <= centerY + 24; y++) {
                for (int x = centerX - 24; x <= centerX + 24; x++) {
                    foreach (var prompt in Prompts) {
                        int score = 0;
                        foreach (var sample in prompt.Samples) {
                            int difference = Difference(pixels, bitmap.Stride, x, y, sample);
                            if (difference > 7) { score = int.MaxValue; break; }
                            score += difference;
                        }
                        var point = new Point(x + prompt.Cancel.X, y + prompt.Cancel.Y);
                        if (score < best) {
                            best = score;
                            target = point;
                            product = prompt.Product;
                            ambiguous = false;
                        } else if (score == best && target.HasValue
                                && Math.Abs(point.X-target.Value.X) + Math.Abs(point.Y-target.Value.Y) > 2) {
                            ambiguous = true;
                        }
                    }
                }
            }
            return ambiguous ? null : target;
        } finally { image.UnlockBits(bitmap); }
    }

    private static int Difference(byte[] pixels, int stride, int x, int y, byte[] sample) {
        int different = 0;
        for (int offset = 0; offset < sample.Length; offset += 4) {
            int dx = BitConverter.ToInt16(sample, offset);
            int dy = BitConverter.ToInt16(sample, offset + 2);
            int at = (y + dy) * stride + (x + dx) * 4;
            int b = pixels[at], g = pixels[at+1], r = pixels[at+2];
            bool matched = offset < 36 * 4
                ? Math.Max(Math.Max(r, g), b) <= 138
                : Math.Min(Math.Min(r, g), b) >= 185;
            if (!matched && ++different > 7) break;
        }
        return different;
    }

    private static bool Capture(Bitmap image, Rectangle area) {
        var screen = GetDC(IntPtr.Zero);
        if (screen == IntPtr.Zero) return false;
        try {
            using (var graphics = Graphics.FromImage(image)) {
                var destination = graphics.GetHdc();
                try {
                    const uint sourceCopy = 0x00CC0020;
                    const uint includeLayeredWindows = 0x40000000;
                    return BitBlt(destination, 0, 0, area.Width, area.Height, screen, area.Left, area.Top,
                        sourceCopy | includeLayeredWindows);
                } finally { graphics.ReleaseHdc(destination); }
            }
        } finally { ReleaseDC(IntPtr.Zero, screen); }
    }

    public static string Dismiss() {
        if (!DefaultDesktop()) return null;
        IntPtr window = GetForegroundWindow();
        if (window == IntPtr.Zero) return null;
        var screen = Screen.PrimaryScreen;
        var work = screen.WorkingArea;
        var center = new Point(work.Left + work.Width / 2, work.Top + work.Height / 2);
        var capture = new Rectangle(center.X - 260, center.Y - 255, 520, 510);
        if (!screen.Bounds.Contains(capture)) return null;
        Point? candidate;
        string product;
        using (var image = new Bitmap(capture.Width, capture.Height, PixelFormat.Format32bppArgb)) {
            if (!Capture(image, capture)) return null;
            candidate = Match(image, 260, 255, out product);
        }
        if (!candidate.HasValue) {
            lastWindow = IntPtr.Zero;
            reportedUnavailableTarget = false;
            return null;
        }
        var point = new Point(capture.Left + candidate.Value.X, capture.Top + candidate.Value.Y);
        var target = GetAncestor(WindowFromPoint(new ScreenPoint(point)), 2); // GA_ROOT
        if (GetForegroundWindow() != window || (GetAsyncKeyState(1) & 0x8000) != 0) return null;
        if (target == IntPtr.Zero || target != GetAncestor(window, 2) || !WindowsDialog(target)) {
            if (reportedUnavailableTarget) return null;
            reportedUnavailableTarget = true;
            return product + " prompt recognized, but its Cancel target was unavailable in the active Windows dialog.";
        }
        if (lastWindow == window && lastTarget == point) return null;
        lastWindow = window;
        lastTarget = point;
        ScreenPoint original;
        if (!GetCursorPos(out original) || GetForegroundWindow() != window || !DefaultDesktop()
                || GetAncestor(WindowFromPoint(new ScreenPoint(point)), 2) != target) return null;
        var desktop = SystemInformation.VirtualScreen;
        var inputs = new[] {
            Move(new ScreenPoint(point), desktop),
            new Input { Mouse = new MouseInput { Flags = 2 } },
            new Input { Mouse = new MouseInput { Flags = 4 } },
            Move(original, desktop)
        };
        return SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(Input))) == inputs.Length
            ? "Selected Cancel on the " + product + " Windows Security dialog."
            : "Windows did not accept input for the " + product + " Windows Security dialog.";
    }

    private static Input Move(ScreenPoint point, Rectangle desktop) {
        return new Input { Mouse = new MouseInput {
            X = (point.X - desktop.Left) * 65535 / (desktop.Width - 1),
            Y = (point.Y - desktop.Top) * 65535 / (desktop.Height - 1),
            Flags = 0xC001 // MOVE | ABSOLUTE | VIRTUALDESK
        } };
    }
}
'@
}
