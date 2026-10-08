using System;
using System.Drawing;
using System.Windows.Forms;

namespace ActasCti
{
    /// <summary>
    /// KT 비밀번호 만료 안내. 두 가지 중에 고르게 한다.
    ///
    ///   [90일 연장]      비밀번호를 그대로 두고 기간만 미룬다 (규격서 3.7.4)
    ///   [비밀번호 변경]   새 비밀번호로 바꾼다 (규격서 3.7.3)
    ///
    /// 연장이 만료된 뒤에도 되는지는 규격서에 없다. 그래서 눌러 보고 안 되면
    /// 변경을 권하는 식으로 안내한다.
    /// </summary>
    public class PasswordForm : Form
    {
        readonly KtAgent agent;
        readonly Config  cfg;
        readonly bool    expired;

        Label   lbMsg;
        TextBox tbNew, tbNew2;
        Button  btExtend, btChange;

        public PasswordForm(KtAgent agent, Config cfg, int daysLeft, bool expired)
        {
            this.agent   = agent;
            this.cfg     = cfg;
            this.expired = expired;

            Build(daysLeft);
        }

        void Build(int daysLeft)
        {
            Text            = "KT 비밀번호";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition   = FormStartPosition.CenterScreen;
            MaximizeBox     = false;
            MinimizeBox     = false;
            Font            = new Font("맑은 고딕", 9F);

            int y = 16;

            // 머리말 — 지금 어떤 상황인지부터 분명히 한다
            var head = new Label
            {
                Text      = expired ? "비밀번호 기간이 지났습니다"
                                    : "비밀번호 기간이 곧 끝납니다",
                Location  = new Point(16, y),
                Size      = new Size(430, 24),
                Font      = new Font("맑은 고딕", 11F, FontStyle.Bold),
                ForeColor = expired ? Color.FromArgb(176, 58, 26) : Color.FromArgb(44, 79, 158)
            };
            Controls.Add(head);
            y += 30;

            lbMsg = new Label
            {
                Text = expired
                    ? "지금은 전화가 화면에 뜨지 않습니다.\n기간을 연장하거나 비밀번호를 바꿔주세요."
                    : "앞으로 " + Math.Max(daysLeft, 0) + "일 뒤면 전화가 화면에 뜨지 않습니다.\n" +
                      "지금 연장해 두시면 90일 더 쓸 수 있습니다.",
                Location = new Point(16, y),
                Size     = new Size(430, 40),
                ForeColor = Color.FromArgb(74, 85, 104)
            };
            Controls.Add(lbMsg);
            y += 50;

            // ── 연장 ──
            btExtend = new Button
            {
                Text     = "90일 연장  (권장)",
                Location = new Point(16, y),
                Size     = new Size(430, 36)
            };
            btExtend.Click += Extend;
            Controls.Add(btExtend);
            y += 42;

            Controls.Add(new Label
            {
                Text      = "비밀번호를 그대로 두고 기간만 미룹니다. 설정은 고치지 않아도 됩니다.",
                Location  = new Point(18, y),
                Size      = new Size(430, 18),
                ForeColor = Color.Gray,
                Font      = new Font("맑은 고딕", 8F)
            });
            y += 30;

            Controls.Add(new Label
            {
                Text     = "또는 새 비밀번호로 바꾸기",
                Location = new Point(16, y),
                Size     = new Size(430, 20),
                Font     = new Font("맑은 고딕", 9F, FontStyle.Bold)
            });
            y += 26;

            tbNew  = AddPw("새 비밀번호", ref y);
            tbNew2 = AddPw("다시 입력",   ref y);

            Controls.Add(new Label
            {
                Text      = Codes.PasswdRule,
                Location  = new Point(18, y),
                Size      = new Size(430, 34),
                ForeColor = Color.Gray,
                Font      = new Font("맑은 고딕", 8F)
            });
            y += 42;

            btChange = new Button
            {
                Text     = "비밀번호 변경",
                Location = new Point(16, y),
                Size     = new Size(430, 32)
            };
            btChange.Click += Change;
            Controls.Add(btChange);
            y += 44;

            var later = new Button
            {
                Text     = expired ? "닫기" : "나중에",
                Location = new Point(356, y),
                Size     = new Size(90, 28),
                DialogResult = DialogResult.Cancel
            };
            later.Click += Later;
            Controls.Add(later);
            CancelButton = later;

            ClientSize = new Size(462, y + 44);
        }

        TextBox AddPw(string label, ref int y)
        {
            Controls.Add(new Label
            {
                Text      = label,
                Location  = new Point(18, y + 4),
                Size      = new Size(90, 20),
                TextAlign = ContentAlignment.MiddleLeft
            });

            var tb = new TextBox
            {
                Location = new Point(112, y),
                Size     = new Size(334, 24),
                UseSystemPasswordChar = true
            };
            Controls.Add(tb);
            y += 30;
            return tb;
        }

        // ── 동작 ─────────────────────────────────────────────────

        void Extend(object sender, EventArgs e)
        {
            Enabled = false;
            int rc = agent.ExtendPassword();
            Enabled = true;

            if (rc == 200)
            {
                Info("90일 연장했습니다.\n비밀번호는 그대로입니다.");
                DialogResult = DialogResult.OK;
                Close();
                return;
            }

            // 만료된 뒤에는 연장이 안 될 수 있다. 그때는 변경으로 안내한다.
            Warn("연장하지 못했습니다. (" + Codes.PasswdExtendMsg(rc) + ")\n\n" +
                 "아래에서 비밀번호를 바꿔주세요.");
            tbNew.Focus();
        }

        void Change(object sender, EventArgs e)
        {
            var pw1 = tbNew.Text;
            var pw2 = tbNew2.Text;

            if (pw1.Length == 0) { Warn("새 비밀번호를 넣어주세요."); tbNew.Focus(); return; }
            if (pw1 != pw2)      { Warn("두 번 넣은 비밀번호가 다릅니다."); tbNew2.Focus(); return; }
            if (pw1.Length < 8)  { Warn("8자 이상이어야 합니다."); tbNew.Focus(); return; }
            if (pw1 == cfg.KtLoginPw) { Warn("지금 쓰는 비밀번호와 같습니다."); tbNew.Focus(); return; }

            Enabled = false;
            int rc = agent.ChangePassword(cfg.KtLoginPw, pw1);
            Enabled = true;

            if (rc == 200)
            {
                Info("비밀번호를 바꿨습니다.\n설정에도 새 비밀번호를 넣었습니다.");
                DialogResult = DialogResult.OK;
                Close();
                return;
            }

            Warn("바꾸지 못했습니다.\n\n" + Codes.PasswdChangeMsg(rc));
            tbNew.Focus();
        }

        void Later(object sender, EventArgs e)
        {
            // 만료 전이면 오늘은 더 묻지 않는다. 이미 만료됐으면 미루는 의미가 없다.
            if (expired) return;

            cfg.PwSnoozed = Config.Today;
            cfg.Save();
            Logger.Write("[비밀번호] 오늘은 더 묻지 않습니다.");
        }

        void Info(string m)
        {
            MessageBox.Show(this, m, "ACTAS 전화연동",
                            MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        void Warn(string m)
        {
            MessageBox.Show(this, m, "ACTAS 전화연동",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }
}
