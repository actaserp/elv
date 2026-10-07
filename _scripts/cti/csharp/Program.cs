using System;
using System.Diagnostics;
using System.Drawing;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace ActasCti
{
    /// <summary>
    /// ACTAS 전화연동 — 트레이에 상주하며 KT 전화를 받아 elv 로 넘긴다.
    /// 창은 없고 작업표시줄 시계 옆 아이콘으로만 보인다.
    /// </summary>
    static class Program
    {
        static NotifyIcon tray;
        static KtAgent agent;
        static Config cfg;
        static ToolStripMenuItem miConnect, miDisconnect;

        [STAThread]
        static void Main()
        {
            // 두 개가 동시에 돌면 같은 KT 계정으로 서로 밀어낸다. 하나만 뜨게 한다.
            bool isNew;
            using (var mutex = new Mutex(true, "ACTAS_전화연동_single", out isNew))
            {
                if (!isNew)
                {
                    MessageBox.Show("ACTAS 전화연동이 이미 실행 중입니다.\n\n작업표시줄 오른쪽 아이콘을 확인해주세요.",
                                    "ACTAS 전화연동", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                Logger.Cleanup();

                // 사업체가 로그를 보내올 때 어느 버전인지 알아야 한다
                Logger.Write("───── ACTAS 전화연동 " + Version() + " 시작 ─────");

                cfg = Config.Load();

                BuildTray();

                // 설정이 비어 있으면 먼저 받는다 (설치 후 첫 실행)
                if (!cfg.IsReady && !ShowSettings())
                {
                    tray.Dispose();
                    return;
                }

                try
                {
                    agent = new KtAgent(cfg);
                    agent.StateChanged += OnStateChanged;
                    agent.CallReceived += OnCallReceived;
                    agent.Start();
                }
                catch (Exception e)
                {
                    Logger.Write("시작 실패: " + e);
                    MessageBox.Show(
                        "전화 장치에 연결하지 못했습니다.\n\n프로그램을 다시 설치해주세요.\n\n" + e.Message,
                        "ACTAS 전화연동", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    tray.Dispose();
                    return;
                }

                Application.Run();
                tray.Dispose();
            }
        }

        // ── 트레이 ───────────────────────────────────────────────

        static void BuildTray()
        {
            var menu = new ContextMenuStrip();

            miConnect    = new ToolStripMenuItem("연결", null, (s, e) => agent.Login());
            miDisconnect = new ToolStripMenuItem("해제", null, (s, e) => agent.Logout());

            menu.Items.Add(miConnect);
            menu.Items.Add(miDisconnect);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("설정", null, (s, e) => ChangeSettings()));
            menu.Items.Add(new ToolStripMenuItem("로그 보기", null, (s, e) => OpenLogs()));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("종료", null, (s, e) => Quit()));

            tray = new NotifyIcon
            {
                Icon = MakeIcon(Color.Gray),
                Text = "ACTAS 전화연동 — 대기 중",
                ContextMenuStrip = menu,
                Visible = true
            };
            tray.DoubleClick += (s, e) => ChangeSettings();

            // 알림을 누르면 내려놨던 화면을 올려준다
            tray.BalloonTipClicked += (s, e) => OnBalloonClick();

            UpdateMenu(false);
        }

        static void OnStateChanged(string message, bool connected)
        {
            // COM 이벤트는 다른 스레드에서 온다. UI 는 반드시 제 스레드에서 건드린다.
            if (tray == null) return;

            Action apply = () =>
            {
                tray.Icon = MakeIcon(connected ? Color.FromArgb(46, 125, 50) : Color.Gray);
                tray.Text = Cut("ACTAS 전화연동 — " + message, 63);   // 윈도우 제한
                UpdateMenu(connected);
            };

            if (tray.ContextMenuStrip.InvokeRequired) tray.ContextMenuStrip.BeginInvoke(apply);
            else apply();
        }

        /// <summary>
        /// 전화가 오면 윈도우 알림을 띄운다.
        /// 벨이 울릴 때(201)와 못 받았을 때(부재중)만 띄우고, 중간 상태는 넘긴다 —
        /// 한 통화에 이벤트가 여러 번 와서 다 띄우면 알림이 쏟아진다.
        /// </summary>
        static void OnCallReceived(string caller, string result)
        {
            bool ringing = Codes.IsRinging(result);
            bool missed  = Codes.IsMissed(result);
            if (!ringing && !missed) return;

            var title = ringing ? "전화 수신" : "부재중 전화";
            var body  = FormatTel(caller);

            Action show = () =>
                tray.ShowBalloonTip(ringing ? 20000 : 10000, title, body, ToolTipIcon.Info);

            if (tray.ContextMenuStrip.InvokeRequired) tray.ContextMenuStrip.BeginInvoke(show);
            else show();
        }

        /// <summary>
        /// 알림을 눌렀다 — 전화를 받으려면 화면이 앞에 있어야 한다.
        /// </summary>
        static void OnBalloonClick()
        {
            // 탭을 훑는 동안 트레이 메뉴가 멈추면 안 되므로 딴 스레드에서 한다
            var t = new Thread(() =>
            {
                try
                {
                    if (!WindowFinder.BringUp(cfg.SiteUrl(), cfg.TabTokens(), cfg.DebugTabs))
                        Logger.Write("알림 클릭 — 올릴 창을 찾지 못했습니다.");
                }
                catch (Exception e) { Logger.Write("알림 클릭 처리 오류: " + e.Message); }
            });
            t.IsBackground = true;
            t.SetApartmentState(ApartmentState.MTA);   // UI Automation 권장
            t.Start();
        }

        /// <summary>01012345678 → 010-1234-5678</summary>
        static string FormatTel(string num)
        {
            if (string.IsNullOrEmpty(num)) return "";

            var d = new StringBuilder();
            foreach (var ch in num) if (char.IsDigit(ch)) d.Append(ch);
            var s = d.ToString();

            if (s.StartsWith("02") && s.Length >= 9)
                return s.Substring(0, 2) + "-" + s.Substring(2, s.Length - 6) + "-" + s.Substring(s.Length - 4);
            if (s.Length == 10 || s.Length == 11)
                return s.Substring(0, 3) + "-" + s.Substring(3, s.Length - 7) + "-" + s.Substring(s.Length - 4);
            return s;
        }

        static void UpdateMenu(bool connected)
        {
            miConnect.Enabled    = !connected;
            miDisconnect.Enabled = connected;
        }

        /// <summary>상태를 색으로 보여주는 작은 아이콘을 그린다 (별도 파일 없이)</summary>
        static Icon MakeIcon(Color color)
        {
            using (var bmp = new Bitmap(16, 16))
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                using (var b = new SolidBrush(color)) g.FillEllipse(b, 2, 2, 12, 12);
                using (var p = new Pen(Color.FromArgb(80, 0, 0, 0))) g.DrawEllipse(p, 2, 2, 12, 12);
                return Icon.FromHandle(bmp.GetHicon());
            }
        }

        static string Cut(string s, int max)
        {
            return s.Length <= max ? s : s.Substring(0, max);
        }

        /// <summary>AssemblyInfo.cs 에 적어 둔 버전 (예: 1.0.0)</summary>
        static string Version()
        {
            try
            {
                var v = Assembly.GetExecutingAssembly().GetName().Version;
                return v.Major + "." + v.Minor + "." + v.Build;
            }
            catch { return "?"; }
        }

        // ── 메뉴 동작 ────────────────────────────────────────────

        static bool ShowSettings()
        {
            using (var f = new SettingsForm(cfg))
                return f.ShowDialog() == DialogResult.OK;
        }

        static void ChangeSettings()
        {
            if (!ShowSettings()) return;

            // 계정이 바뀌었으면 다시 붙어야 한다
            if (agent != null && agent.LoggedIn) agent.Logout();
        }

        static void OpenLogs()
        {
            try { Process.Start("explorer.exe", Logger.Folder); }
            catch (Exception e) { MessageBox.Show("로그 폴더를 열지 못했습니다.\n" + e.Message); }
        }

        static void Quit()
        {
            if (agent != null && agent.LoggedIn)
            {
                var r = MessageBox.Show(
                    "지금 전화를 받는 중입니다.\n종료하면 전화가 화면에 뜨지 않습니다.\n\n종료할까요?",
                    "ACTAS 전화연동", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                if (r != DialogResult.Yes) return;
            }

            if (agent != null) agent.Stop();
            tray.Visible = false;
            Application.Exit();
        }
    }
}
