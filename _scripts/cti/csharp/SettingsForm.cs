using System;
using System.Drawing;
using System.Windows.Forms;

namespace ActasCti
{
    /// <summary>
    /// 설정 창. 설치 후 한 번만 채우면 된다.
    /// 비밀번호는 가려서 입력받고, 저장할 때 이 PC 에서만 풀리도록 암호화한다.
    /// </summary>
    public class SettingsForm : Form
    {
        readonly Config cfg;

        TextBox tbKtId, tbKtPw, tbElvUser, tbUrl, tbSecret, tbAuthKey;
        CheckBox cbAuto;

        public SettingsForm(Config config)
        {
            cfg = config;
            Build();
            Fill();
        }

        void Build()
        {
            Text            = "ACTAS 전화연동 설정";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition   = FormStartPosition.CenterScreen;
            MaximizeBox     = false;
            MinimizeBox     = false;
            Font            = new Font("맑은 고딕", 9F);
            // 높이는 항목을 다 배치한 뒤 Build() 끝에서 정한다 — 항목이 늘어도 잘리지 않게

            int y = 16;

            AddTitle("KT 통화매니저 계정", ref y);
            tbKtId    = AddField("아이디", "전화번호@kt.com 형식", ref y);
            tbKtPw    = AddField("비밀번호", null, ref y);
            tbKtPw.UseSystemPasswordChar = true;

            y += 8;
            AddTitle("전화를 받을 담당자", ref y);
            tbElvUser = AddField("elv 아이디", "이 계정 화면에 전화가 뜹니다", ref y);

            y += 8;
            AddTitle("서버 (담당자가 알려드립니다)", ref y);
            tbAuthKey = AddField("인증키", null, ref y);
            tbUrl     = AddField("주소", null, ref y);
            tbSecret  = AddField("연결키", null, ref y);
            tbSecret.UseSystemPasswordChar = true;

            cbAuto = new CheckBox
            {
                Text     = "PC 를 켤 때 자동으로 연결",
                Location = new Point(110, y),
                Size     = new Size(300, 22)
            };
            Controls.Add(cbAuto);
            y += 34;

            var ok = new Button { Text = "저장", Location = new Point(236, y), Size = new Size(90, 30) };
            ok.Click += Save;

            var cancel = new Button
            {
                Text = "취소", Location = new Point(334, y), Size = new Size(90, 30),
                DialogResult = DialogResult.Cancel
            };

            Controls.Add(ok);
            Controls.Add(cancel);
            AcceptButton = ok;
            CancelButton = cancel;

            ClientSize = new Size(440, y + 46);   // 버튼 아래 여백까지
        }

        void AddTitle(string text, ref int y)
        {
            Controls.Add(new Label
            {
                Text      = text,
                Location  = new Point(16, y),
                Size      = new Size(410, 20),
                Font      = new Font("맑은 고딕", 9F, FontStyle.Bold),
                ForeColor = Color.FromArgb(44, 79, 158)
            });
            y += 26;
        }

        TextBox AddField(string label, string hint, ref int y)
        {
            Controls.Add(new Label
            {
                Text      = label,
                Location  = new Point(24, y + 4),
                Size      = new Size(80, 20),
                TextAlign = ContentAlignment.MiddleLeft
            });

            var tb = new TextBox { Location = new Point(110, y), Size = new Size(310, 24) };
            Controls.Add(tb);
            y += 28;

            if (hint != null)
            {
                Controls.Add(new Label
                {
                    Text      = hint,
                    Location  = new Point(112, y - 2),
                    Size      = new Size(310, 16),
                    ForeColor = Color.Gray,
                    Font      = new Font("맑은 고딕", 8F)
                });
                y += 16;
            }
            return tb;
        }

        void Fill()
        {
            tbKtId.Text    = cfg.KtLoginId;
            tbKtPw.Text    = cfg.KtLoginPw;
            tbElvUser.Text = cfg.ElvUsername;
            tbAuthKey.Text = cfg.KtAuthKey;
            tbUrl.Text     = cfg.ElvUrl;
            tbSecret.Text  = cfg.ElvSecret;
            cbAuto.Checked = cfg.AutoLogin;
        }

        void Save(object sender, EventArgs e)
        {
            if (!Require(tbKtId,    "KT 아이디를"))    return;
            if (!Require(tbKtPw,    "KT 비밀번호를"))  return;
            if (!Require(tbElvUser, "elv 아이디를"))   return;
            if (!Require(tbAuthKey, "인증키를"))       return;
            if (!Require(tbSecret,  "연결키를"))       return;

            cfg.KtLoginId   = tbKtId.Text.Trim();
            cfg.KtLoginPw   = tbKtPw.Text;
            cfg.ElvUsername = tbElvUser.Text.Trim();
            cfg.KtAuthKey   = tbAuthKey.Text.Trim();
            cfg.ElvUrl      = tbUrl.Text.Trim();
            cfg.ElvSecret   = tbSecret.Text.Trim();
            cfg.AutoLogin   = cbAuto.Checked;

            try
            {
                cfg.Save();
                Logger.Write("설정을 저장했습니다. id=" + cfg.KtLoginId + ", elv=" + cfg.ElvUsername);
                DialogResult = DialogResult.OK;
                Close();
            }
            catch (Exception ex)
            {
                MessageBox.Show("설정을 저장하지 못했습니다.\n\n" + ex.Message,
                                "ACTAS 전화연동", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        bool Require(TextBox tb, string what)
        {
            if (tb.Text.Trim().Length > 0) return true;

            MessageBox.Show(what + " 입력해주세요.", "ACTAS 전화연동",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
            tb.Focus();
            return false;
        }
    }
}
