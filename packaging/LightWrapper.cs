using System;
using System.Diagnostics;
using System.IO;
using System.Text;

// Wrapper around WiX light.exe that appends "-sval" (skip ICE validation) on
// real link invocations. ICE validation requires the Windows Installer Service,
// which is unavailable in the sandbox and makes light.exe fail with exit 216.
//
// The real light.exe must live in a `real/` subdirectory (keeping its original
// file name, since WiX loads wix.dll based on the exe name); a copy of wix.dll
// is placed alongside it. Version detection (light.exe /?) is forwarded without
// -sval so jpackage can still read the version string.
internal static class LightWrapper
{
    private static int Main(string[] args)
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        string real = Path.Combine(dir, "real", "light.exe");
        if (!File.Exists(real))
        {
            Console.Error.WriteLine("real/light.exe not found: " + real);
            return 1;
        }

        bool isLink = false;
        foreach (string a in args)
        {
            if (a == "-out")
            {
                isLink = true;
                break;
            }
        }

        var sb = new StringBuilder();
        foreach (string a in args)
        {
            sb.Append('"').Append(a.Replace("\"", "\\\"")).Append("\" ");
        }
        if (isLink)
        {
            sb.Append("-sval");
        }

        var psi = new ProcessStartInfo(real, sb.ToString())
        {
            UseShellExecute = false,
        };
        var p = Process.Start(psi);
        p.WaitForExit();
        return p.ExitCode;
    }
}
