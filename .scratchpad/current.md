# 現在の状況 — PR #45（2026-11-05）

- 対象：[PR #45](https://github.com/choko510/DiPlay/pull/45) / `codex/step4-pr-00-test-baseline`。整理直前HEAD `7198329afeb0c77fe57a7d861e26a2ba7b651d02`、固定比較先main `95ba398f95ee104527812cd2b095977d71c51f98`。PRはopen・未マージ（2026-11-05確認時は `draft=false`）。
- PR #52のDSP Studioコード統合済み。PR #45の事前修正 `4b2a4a4` によりP1-01（非表示DSPのBack横取り防止）、P1-02（Workspace/旧custom-profile同時保存とジャーナル復旧）、P2-01（Host側退出時のSave/Discard/Continue連携）を実装。
- 当該修正時のローカル確認：Common unit **374/374 PASS**、API 36.1のP1 Activity instrumentation **2/2 PASS**、AndroidTest Kotlinコンパイル、mobile/automotive Debugビルド・lint PASS。これは**当時のSHA**の結果であり現行HEAD全体の再検証ではない。
- [Actions #599](https://github.com/choko510/DiPlay/actions/runs/41076939717) は `91681cc9` の変更分類・公開ツリー・Common Draft unit・quick baselineを通過。Shared testはFROM-CACHE、native/ASan/UBSan、lint/build/JNI/R8、API 35 instrumentationはスキップ。[#600](https://github.com/choko510/DiPlay/actions/runs/41078600112)もdocs-only静的チェックで、製品コードの再検証とは別。
- P2 Compose操作テストはコンパイルするが、API 36.1のEspressoが `InputManager.getInstance()` 不在でテスト実行前に停止（`BLOCKED_ENV`）。API 35での操作検証が必要。
- SH M20 + iPhone 17 Pro Max（iOS 27.0.1）の映像・音声・再接続・Split 2回はユーザー確認の部分証拠。正式なTS10/K706対象のハードウェア受入れには流用不可。
- **未達：** TS10/K706 + iPhoneでのNCM/通話/音声/DSP/Surface/Touch、A/V・CPU・熱・長時間soak、正式署名による更新/復旧、修正後独立レビュー。現時点でリリース可と主張しない。

次の作業は [backlog.md](backlog.md)、壊してはいけない仕様は [decisions.md](decisions.md) を参照。

## 車速連動オーディオ（Draft PR、ブランチ `t3/dd1d14c3`）

- 実装範囲: `shared/.../speed/`（GPS取得・推定・平滑化・カーブ/設定）、`shared/.../dsp/DspSpeedCompensationConfig.kt`（transient config）、native `dsp_speed_comp.c`（180Hz/4kHz分割、40msランプ、無効時bit-exact）、JNI `nativeSetSpeedCompConfig` + `nativeApplyHotConfig` 拡張、DSP HOT経路（`DspRuntimeField.SPEED_COMPENSATION`）、common `DspSpeedCompensationStore`/`Runtime`（単一GPS購読・200ms tick・自動ヘッドルーム予約）、DSP Studio「車速」タブ。初期値はOFF。
- 自動ヘッドルーム予約は「実際にゲインを動かせる間」だけ確保し、減衰中は縮めない（プリAMPの再適用とパイプライン再構築を防ぐ）。ホスト離脱時は即停止せず0dBまで戻してから停止する。
- コードレビュー指摘への対応: 車速HOT更新は「ヘッドルーム由来のPREAMPが動く場合はHOTに入らない」（`DspUpdatePlanner.speedCompensationKeepsPreampStable`）ようにし、予約量の変更はパイプライン交換でPREAMPと同時適用。初回起動のattachは`get()`で確実化、DSP分割表示の権限ランチャー追加、`update`/`updateSpeedCompensation`をCASループで原子化、画面非表示/破棄でテスト速度解除、通知間引きは「最後に通知した値」と比較、速度ジャンプは加速度判定、SharedPreferencesの型破損は安全な既定値。
- 未対応（backlog記載）: バックグラウンド再生中の補正継続、既存位置情報提供とのGPS購読共有、カーブ速度ポイントのUI編集、適用世代の厳密表示。
- ローカル検証（当該作業ツリー）: `:shared` + `:common` unit **1183 PASS**、native DSPテスト PASS、`:mobile`/`:automotive` debugビルド PASS、app module `lintDebug` PASS、R8後JNIマッピング検証 PASS、`check_public_tree.py` PASS。ASan/UBSanはローカルMSYS2に `-lasan`/`-lubsan` が無く **BLOCKED_ENV**（CIで確認）。
- **未検証:** TS10/K706実機でのGPS速度精度・実際の音量変化・通話/ナビ/アシスタントへの非適用・バックグラウンド再生中の挙動・CPU負荷。リリース可と主張しない。
