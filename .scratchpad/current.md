# 現在の状況

## PR #81 — CarPlay表示中の設定画面が奪われる問題（2026-11-20）

- 対象：[PR #81](https://github.com/choko510/DiPlay/pull/81) / `fix/carplay-focus-settings-navigation`。base は `04c8818` の main。**Draft・未マージ**。
- 原因は`DiPlayActivity.onResume()`が設定/メニュー遷移も「実アプリ起動」とみなし、USB自動起動のFOREGROUND要求で`CarPlayHostActivity`を`REORDER_TO_FRONT`していたこと。`DiPlayUiFocus`（前面画面の所有者）と`DiPlayLaunchOrigin`（起動種別）、OEM要求ゲートを導入し、接続処理と画面表示を分離した。
- ローカル確認（当該SHA）：Common unit 404 PASS、Shared unit 756 PASS、mobile/automotive の lintDebug・assembleDebug PASS、`git diff --check` OK。修正前挙動に戻すと新規テスト10件が失敗することを確認済み。
- **未検証：** 実機/ADBなし。有線・無線の画面遷移、`ActivityTaskManager`/`xcertplay-usb-auto`ログ、InstrumentationでのTask Stack検証は未実施。

## PR #45（2026-11-05）

- 対象：[PR #45](https://github.com/choko510/DiPlay/pull/45) / `codex/step4-pr-00-test-baseline`。整理直前HEAD `7198329afeb0c77fe57a7d861e26a2ba7b651d02`、固定比較先main `95ba398f95ee104527812cd2b095977d71c51f98`。PRはopen・未マージ（2026-11-05確認時は `draft=false`）。
- PR #52のDSP Studioコード統合済み。PR #45の事前修正 `4b2a4a4` によりP1-01（非表示DSPのBack横取り防止）、P1-02（Workspace/旧custom-profile同時保存とジャーナル復旧）、P2-01（Host側退出時のSave/Discard/Continue連携）を実装。
- 当該修正時のローカル確認：Common unit **374/374 PASS**、API 36.1のP1 Activity instrumentation **2/2 PASS**、AndroidTest Kotlinコンパイル、mobile/automotive Debugビルド・lint PASS。これは**当時のSHA**の結果であり現行HEAD全体の再検証ではない。
- [Actions #599](https://github.com/choko510/DiPlay/actions/runs/41076939717) は `91681cc9` の変更分類・公開ツリー・Common Draft unit・quick baselineを通過。Shared testはFROM-CACHE、native/ASan/UBSan、lint/build/JNI/R8、API 35 instrumentationはスキップ。[#600](https://github.com/choko510/DiPlay/actions/runs/41078600112)もdocs-only静的チェックで、製品コードの再検証とは別。
- P2 Compose操作テストはコンパイルするが、API 36.1のEspressoが `InputManager.getInstance()` 不在でテスト実行前に停止（`BLOCKED_ENV`）。API 35での操作検証が必要。
- SH M20 + iPhone 17 Pro Max（iOS 27.0.1）の映像・音声・再接続・Split 2回はユーザー確認の部分証拠。正式なTS10/K706対象のハードウェア受入れには流用不可。
- **未達：** TS10/K706 + iPhoneでのNCM/通話/音声/DSP/Surface/Touch、A/V・CPU・熱・長時間soak、正式署名による更新/復旧、修正後独立レビュー。現時点でリリース可と主張しない。

次の作業は [backlog.md](backlog.md)、壊してはいけない仕様は [decisions.md](decisions.md) を参照。
