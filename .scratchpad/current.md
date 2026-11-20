# 現在の状況 — 自動接続信頼性修正 PR #82（2026-11-20）

- 対象：[PR #82](https://github.com/choko510/DiPlay/pull/82) / `fix/auto-connect-reliability`。base `main` `04c8818`、Draft・未マージ。
- `AutoConnectCoordinator` で「DiPlayを開いたときに接続」の要求・重複排除・権限待機・上限付き再試行・明示的切断の抑制を一元化。`initialLaunch` の一回限り判定を廃止し、Activity再生成/`onNewIntent`/権限復帰で同じ起動キーを再利用する。
- レビューで指摘されたP1を修正：再試行上限/権限拒否は新しい起動キーでリセット、通知権限・Bluetooth権限ダイアログ中のActivity再生成でもtransport/世代/iPhone選択待ちを保持、自動接続OFF時の既存ホスト再試行を実行時に取消、初回iPhone選択後も自動要求世代を維持、OS設定で権限付与後の再起動でブロックを解除。
- 追加で、権限ダイアログ待機は20秒で失効させず、`startActivity`成功後は同一起動キーの二重起動を抑止。USB/手動開始は自動セッションの復旧抑制を解除。再試行は元要求のtransportを使い、USB自動起動中は無線再試行しない。
- 再レビューP1を追加修正：`onNewIntent`は新しいユーザー起動/BOOT/USB通知で終端状態（BLOCKED/EXHAUSTED）のときだけ新しい起動キーを発行し、内部設定遷移や再配送では維持。iPhone選択待ちを`WAITING_FOR_USER`として独立させ、Activity再生成後に選択ダイアログを復元。ホストの作成・セッション成立/終了・起動失敗をCoordinatorへ直接通知し、ホームのポーリング依存を解消。
- `BOOT_COMPLETED` は起動前に通知を用意し、Activity起動後に取消すフォールバックへ変更。Android/OEMが例外なくActivity起動を拒否してもユーザー操作を残す。
- 自動開始セッションに限り、自動接続OFF・ユーザー切断・必須権限拒否・試行上限で `CarPlayHostActivity.reconnectAfterLoss()` を抑制。手動/USBセッションの既存復旧は維持。
- Bluetooth監視・自動ペアリング・自動接続は追加していない。手動iPhone選択と既存RFCOMM経路は不変。
- ローカル確認：shared unit **756/756 PASS**、common unit **446/446 PASS**、mobile/automotive lint・Debug build・Benchmark build PASS。
- **未検証：** TS10/K706（Android 10）実機のBOOT起動制限・通知タップ、Android 13+通知権限、Bluetooth/無線権限拒否、Activity再生成、USB+無線競合、実CarPlay接続。モック成功を実機接続成功と扱わない。
- **CI未実行：** `workflow_dispatch` は `HTTP 422: Actions has been disabled for this repository` で拒否。API上は `enabled=true` / workflow activeだが外部制約。ローカルCI相当はPASS済み。

次の作業は [backlog.md](backlog.md)、壊してはいけない仕様は [decisions.md](decisions.md) を参照。
