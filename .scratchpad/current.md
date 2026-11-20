# 現在の状況 — 自動接続信頼性修正 PR #82（2026-11-20）

- 対象：[PR #82](https://github.com/choko510/DiPlay/pull/82) / `fix/auto-connect-reliability`。base `main` `04c8818`、Draft・未マージ。実装コミット `aa733d8`。
- `AutoConnectCoordinator` で「DiPlayを開いたときに接続」の要求・重複排除・権限待機・上限付き再試行・明示的切断の抑制を一元化。`initialLaunch` の一回限り判定を廃止し、Activity再生成/`onNewIntent`/権限復帰で同じ起動キーを再利用する。
- `BOOT_COMPLETED` は起動前に通知を用意し、Activity起動後に取消すフォールバックへ変更。Android/OEMが例外なくActivity起動を拒否してもユーザー操作を残す。
- 自動開始セッションに限り、自動接続OFF・ユーザー切断・必須権限拒否・試行上限で `CarPlayHostActivity.reconnectAfterLoss()` を抑制。手動/USBセッションの既存復旧は維持。
- Bluetooth監視・自動ペアリング・自動接続は追加していない。手動iPhone選択と既存RFCOMM経路は不変。
- ローカル確認：shared unit **756/756 PASS**、common unit **419/419 PASS**（新規44件）、mobile/automotive lint・Debug build・Benchmark build PASS。途中のフル実行で既存 `WifiP2pGroupManagerTest` の300ms固定タイムアウトが1回負荷起因で失敗したが、単独・再実行・最終フル実行はPASS。
- **未検証：** TS10/K706（Android 10）実機のBOOT起動制限・通知タップ、Android 13+通知権限、Bluetooth/無線権限拒否、USB+無線競合、実CarPlay接続。モック成功を実機接続成功と扱わない。
- **CI未実行：** PR #82はDraftのためcheck未登録。`workflow_dispatch` は `HTTP 422: Actions has been disabled for this repository` で拒否。API上は `enabled=true` / workflow activeだが外部制約。ローカルCI相当はPASS済み。

次の作業は [backlog.md](backlog.md)、壊してはいけない仕様は [decisions.md](decisions.md) を参照。
