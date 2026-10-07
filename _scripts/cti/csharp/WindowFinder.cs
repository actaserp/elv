using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;

namespace ActasCti
{
    /// <summary>
    /// 알림을 눌렀을 때 우리 화면을 앞으로 올려준다.
    ///
    /// 창을 올리는 것만으로는 부족하다. 브라우저 창 하나에 탭이 여럿이라
    /// 창만 올리면 사용자가 보고 있던 엉뚱한 탭이 나온다(실측으로 확인).
    /// 그래서 탭까지 전환한다.
    ///
    /// 탭 전환은 윈도우 접근성 기능(UI Automation)으로 한다. 브라우저가
    /// 탭을 표준 방식으로 노출하므로 크롬·엣지·웨일·브레이브·파이어폭스에
    /// 같은 코드가 통한다. 브라우저 이름을 알아야 할 일이 없다.
    /// </summary>
    static class WindowFinder
    {
        /// <summary>
        /// 탭을 알아보는 단서. Config.TabTokens() 가 넘겨준다.
        /// 고정 문자열을 쓰면 안 된다 — elv 제목은 사업체마다 다르고(LOGO_TITLE)
        /// 비어 있으면 브라우저가 주소를 대신 보여준다.
        /// </summary>
        static string[] tokens = new string[0];

        /// <summary>
        /// 못 찾았을 때 탭 이름을 로그에 남길지. 기본은 꺼 둔다 —
        /// 사업체 직원 PC 의 브라우저 탭 제목이 로그에 쌓이면 안 된다.
        /// 문제를 쫓을 때만 설정에서 `DEBUG_TABS = 1` 로 켠다.
        /// </summary>
        static bool logCandidates = false;

        /// <summary>
        /// 탭을 찾을 때 내려갈 최대 깊이. 탭 막대는 창 바로 아래쪽에 있다.
        /// 깊이 제한이 없으면 페이지 내용까지 뒤져서 몇 초씩 걸린다.
        /// </summary>
        const int MaxDepth = 8;

        const int SW_MINIMIZE = 6;
        const int SW_RESTORE  = 9;
        const int SW_SHOW     = 5;

        delegate bool EnumProc(IntPtr hwnd, IntPtr param);

        [DllImport("user32.dll")]
        static extern bool EnumWindows(EnumProc cb, IntPtr param);
        [DllImport("user32.dll")]
        static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")]
        static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll")]
        static extern bool ShowWindow(IntPtr hwnd, int cmd);
        [DllImport("user32.dll")]
        static extern bool SetForegroundWindow(IntPtr hwnd);
        [DllImport("user32.dll")]
        static extern int GetWindowTextLength(IntPtr hwnd);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern int GetWindowText(IntPtr hwnd, StringBuilder buf, int max);

        /// <summary>
        /// 화면을 앞으로 올린다. 세 단계로 시도하고, 어느 단계에서 됐는지 로그에 남긴다.
        /// </summary>
        public static bool BringUp(string fallbackUrl, string[] tabTokens, bool debugTabs)
        {
            tokens        = tabTokens ?? new string[0];
            logCandidates = debugTabs;

            // ① 우리 화면이 이미 앞 탭이면 창 제목에 들어온다. 가장 빠르다.
            var hwnd = FindByTitle();
            if (hwnd != IntPtr.Zero)
            {
                Logger.Write("알림 클릭 — 앞 탭이 우리 화면입니다. 창을 올립니다.");
                Raise(hwnd);
                return true;
            }

            // ② 뒤쪽 탭에 있으면 그 탭으로 전환한다.
            if (SelectTab()) return true;

            // ③ 어디에도 안 열려 있으면 새로 띄운다.
            if (!string.IsNullOrEmpty(fallbackUrl))
            {
                Logger.Write("알림 클릭 — 열려 있는 화면이 없어 새로 엽니다: " + fallbackUrl);
                try { Process.Start(fallbackUrl); return true; }
                catch (Exception e) { Logger.Write("화면 열기 실패: " + e.Message); }
            }
            return false;
        }

        // ── ① 창 제목으로 찾기 ──────────────────────────────────

        static IntPtr FindByTitle()
        {
            IntPtr found = IntPtr.Zero;

            EnumWindows((hwnd, param) =>
            {
                if (!IsWindowVisible(hwnd)) return true;

                int len = GetWindowTextLength(hwnd);
                if (len == 0) return true;

                var buf = new StringBuilder(len + 1);
                GetWindowText(hwnd, buf, buf.Capacity);

                if (Matches(buf.ToString()))
                {
                    found = hwnd;
                    return false;   // 찾았으니 그만
                }
                return true;
            }, IntPtr.Zero);

            return found;
        }

        /// <summary>단서 중 하나라도 들어 있으면 우리 화면으로 본다</summary>
        static bool Matches(string name)
        {
            if (string.IsNullOrEmpty(name)) return false;

            foreach (var t in tokens)
                if (!string.IsNullOrEmpty(t) &&
                    name.IndexOf(t, StringComparison.OrdinalIgnoreCase) >= 0) return true;

            return false;
        }

        // ── ② 탭 전환 ───────────────────────────────────────────

        static bool SelectTab()
        {
            // 크롬 계열은 접근성 정보를 평소에 끄고 있다가 물어볼 때 켠다.
            // 첫 조회가 빈손으로 오는 경우가 있어 한 번 더 본다.
            var seen = new List<string>();

            for (int attempt = 1; attempt <= 2; attempt++)
            {
                try
                {
                    if (TrySelectTab(seen)) return true;
                }
                catch (Exception e)
                {
                    Logger.Write("탭 찾기 오류: " + e.Message);
                    return false;
                }

                if (attempt == 1) Thread.Sleep(400);
            }

            Logger.Write("알림 클릭 — 열려 있는 탭 중에 우리 화면이 없습니다. " +
                         "(찾던 단서: " + string.Join(" | ", tokens) + ")");

            // 사업체마다 제목이 달라서, 안 맞을 때 실제 탭 이름을 봐야 고칠 수 있다.
            // 브라우저 탭 제목은 사생활이라 DEBUG_TABS = 1 일 때만 남긴다.
            if (logCandidates)
            {
                Logger.Write("열려 있던 탭 " + seen.Count + "개:");
                for (int i = 0; i < seen.Count && i < 30; i++)
                    Logger.Write("    · " + seen[i]);
            }
            else
            {
                Logger.Write("탭 이름을 보려면 설정 파일에 DEBUG_TABS = 1 을 넣고 다시 눌러주세요.");
            }
            return false;
        }

        static bool TrySelectTab(List<string> seen)
        {
            // 데스크톱의 자식 = 최상위 창들. 각 창에서 탭 막대만 들여다본다.
            var windows = AutomationElement.RootElement.FindAll(
                TreeScope.Children,
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Window));

            foreach (AutomationElement win in windows)
            {
                var tabs = new List<AutomationElement>();
                CollectTabs(win, 0, tabs);

                foreach (var tab in tabs)
                {
                    string name;
                    try { name = tab.Current.Name ?? ""; } catch { continue; }

                    if (!Matches(name))
                    {
                        if (!seen.Contains(name)) seen.Add(name);
                        continue;
                    }

                    Logger.Write("알림 클릭 — 탭으로 전환합니다: " + name);

                    // 창을 먼저 올려야 탭 선택이 화면에 보인다
                    int h = 0;
                    try { h = win.Current.NativeWindowHandle; } catch { }
                    if (h != 0) Raise(new IntPtr(h));

                    try
                    {
                        var sel = tab.GetCurrentPattern(SelectionItemPattern.Pattern)
                                  as SelectionItemPattern;
                        if (sel != null) { sel.Select(); return true; }
                    }
                    catch (Exception e) { Logger.Write("탭 선택 실패: " + e.Message); }

                    // 탭 전환은 못 했지만 창은 올렸다 — 사용자가 탭만 고르면 된다
                    return h != 0;
                }
            }
            return false;
        }

        /// <summary>
        /// 탭 막대를 찾아 내려간다. 페이지 내용(Document)으로는 들어가지 않는다 —
        /// 거기까지 뒤지면 화면 하나에 수천 개라 몇 초씩 걸린다.
        /// </summary>
        static void CollectTabs(AutomationElement el, int depth, List<AutomationElement> found)
        {
            if (depth > MaxDepth || found.Count > 100) return;

            var walker = TreeWalker.ControlViewWalker;

            AutomationElement child;
            try { child = walker.GetFirstChild(el); }
            catch { return; }

            while (child != null)
            {
                ControlType type = null;
                try { type = child.Current.ControlType; } catch { }

                if (type == ControlType.TabItem) found.Add(child);
                else if (type != ControlType.Document) CollectTabs(child, depth + 1, found);

                try { child = walker.GetNextSibling(child); }
                catch { break; }
            }
        }

        // ── 창 올리기 ───────────────────────────────────────────

        static void Raise(IntPtr hwnd)
        {
            if (IsIconic(hwnd)) ShowWindow(hwnd, SW_RESTORE);
            else                ShowWindow(hwnd, SW_SHOW);

            if (SetForegroundWindow(hwnd)) return;

            // 윈도우는 다른 프로그램이 창을 가로채는 것을 막는다.
            // 내렸다 올리면 '사용자가 올린 것'처럼 처리돼 대개 넘어간다.
            ShowWindow(hwnd, SW_MINIMIZE);
            ShowWindow(hwnd, SW_RESTORE);
            SetForegroundWindow(hwnd);
        }
    }
}
