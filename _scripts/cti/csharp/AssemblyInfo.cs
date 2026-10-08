using System.Reflection;
using System.Runtime.InteropServices;

// 사업체가 문제를 알려올 때 "몇 버전을 쓰고 있나"를 알아야 한다.
// 이 값은 exe 속성창에도 보이고, 프로그램이 시작할 때 로그 첫 줄에 남는다.
// 고칠 때마다 올린다 — 기능 추가는 가운데, 버그 수정은 끝자리.
[assembly: AssemblyTitle("ACTAS 전화연동")]
[assembly: AssemblyDescription("KT 통화매니저 전화 수신을 ACTAS ELV 화면으로 넘긴다")]
[assembly: AssemblyCompany("ACTAS")]
[assembly: AssemblyProduct("ACTAS ELV")]
[assembly: AssemblyCopyright("Copyright © ACTAS")]

[assembly: AssemblyVersion("1.6.0.0")]
[assembly: AssemblyFileVersion("1.6.0.0")]

[assembly: ComVisible(false)]
