# 現在の状況

## PR #81 — CarPlay表示中の設定画面が奪われる問題（2026-11-20）

- 対象：[PR #81](https://github.com/choko510/DiPlay/pull/81) / `fix/carplay-focus-settings-navigation`。base は `04c8818` の main。**Draft・未マージ**。
- 原因は`DiPlayActivity.onResume()`が設定/メニュー遷移も「実アプリ起動」とみなし、USB自動起動のFOREGROUND要求で`CarPlayHostActivity`を`REORDER_TO_FRONT`していたこと。`DiPlayUiFocus`（前面画面の所有者）と`DiPlayLaunchOrigin`（起動種別）、OEM要求ゲートを導入し、接続処理と画面表示を分離した。
- ローカル確認（当該SHA）：Common unit 448 PASS、Shared unit 756 PASS、mobile/automotive の lintDebug（0 errors / 4 warnings）・assembleDebug PASS、`git diff --check` OK。修正前挙動に戻すと新規テスト19件＋今回追加分7件が失敗することを確認済み。
- レビュー指摘への対応：同一Activity内のページ変更と`onNewIntent`でフォーカスを同期、遷移前の`navigatingTo`でpause→resume間の空白を保護、起動直前の再検証、ホーム復帰時の保留要求再開（iPhone/CH341）、CH341アタッチ経路の保留化。
- 五度目のレビュー対応：ハンドオーバー再起動に`CarPlayHostRecovery`（WIRED_USB/RESTORE_TRANSPORT）を導入して無線復旧がCH341起動へすり替わらないようにし、保留要求の更新で新しい`hostLaunchState`を採用、USB受信Activityの多重表示を所有者集合で管理。
- 四度目のレビュー対応：透過USB受信Activityを所有権者にせず一時保護のみに変更（終了時に解放、覆われた画面が既に離れていれば残留しない）、ハンドオーバー保留で`hostLaunchState`（分割モード・比率）を保持、CH341の再開要求を`CarPlayUsbAttachDispatcher`で保留して自動再開に統一（通知は保留不可時のフォールバック、権限なしでも安全）。
- 三度目のレビュー対応：`paused`では保護を解除せず`onStop`で解除（ホーム移動後の古い画面を復元しない、pause→受信Activity間の空白を閉じる）、無線→有線ハンドオーバーの非同期再起動を`restartHostAfterShutdown`に集約して表示意図を再検証。
- 再レビュー指摘への対応：Androidが直接起動するUSB受信Activityは`transientOverlayResumed`で覆われた画面を復元、`finish()`で戻る経路は`announceReturn`で戻り先を宣言、起動失敗はトークン付き`navigateFrom`で宣言を取り消し、CH341の保留要求は起動成功時のみ完了した。
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

## 車速連動オーディオ（Draft PR、ブランチ `t3/dd1d14c3`）

- 実装範囲: `shared/.../speed/`（GPS取得・推定・平滑化・カーブ/設定）、`shared/.../dsp/DspSpeedCompensationConfig.kt`（transient config）、native `dsp_speed_comp.c`（180Hz/4kHz分割、40msランプ、無効時bit-exact）、JNI `nativeSetSpeedCompConfig` + `nativeApplyHotConfig` 拡張、DSP HOT経路（`DspRuntimeField.SPEED_COMPENSATION`）、common `DspSpeedCompensationStore`/`Runtime`（単一GPS購読・200ms tick・自動ヘッドルーム予約）、DSP Studio「車速」タブ。初期値はOFF。
- 自動ヘッドルーム予約は「実際にゲインを動かせる間」だけ確保し、減衰中は縮めない（プリAMPの再適用とパイプライン再構築を防ぐ）。ホスト離脱時は即停止せず0dBまで戻してから停止する。
- コードレビュー指摘への対応: 車速HOT更新は「ヘッドルーム由来のPREAMPが動く場合はHOTに入らない」（`DspUpdatePlanner.speedCompensationKeepsPreampStable`）ようにし、予約量の変更はパイプライン交換でPREAMPと同時適用。初回起動のattachは`get()`で確実化、DSP分割表示の権限ランチャー追加、`update`/`updateSpeedCompensation`をCASループで原子化、画面非表示/破棄でテスト速度解除、通知間引きは「最後に通知した値」と比較、速度ジャンプは加速度判定、SharedPreferencesの型破損は安全な既定値。
- 再レビュー対応: GPS有効Fixが無い間はヘッドルームを予約しない（`canBoost`）、`estimator.reset()`をstart/stop/テストモード解除で実行、Auto Headroom OFF時はブースト禁止（`setBoostAllowed`、UI警告）、`CarPlayHostActivity`のdetachをonDestroyへ移して停止中も補正継続、`DspSettingsActivity.onStop`でもテスト速度解除。
- 3次レビュー対応: Renderer統合テスト3本を`DspRendererProductionPathTest`へ追加（予約変更=パイプライン交換、予約一定=HOT、解放=交換。`HotProcessor`で実レンダラ経路を検証）、予約適用後350msはブーストを保留（`setBoostPrimed`）、プロバイダ無効/権限喪失で即座にブースト目標0dB（`sourceBlocked`）、ブースト不可時はGPS購読しない。
- 未対応（backlog記載）: Android バックグラウンド測位（Foreground Service type `location` 未宣言。Activity停止中は測位更新が止まり得る）、既存位置情報提供とのGPS購読共有、カーブ速度ポイントのUI編集、適用世代の厳密表示。
- 既知の音響仕様: 予約は最悪ケース（STANDARD 6.8dB）を先に確保するため、有効化時は一度PREAMPが下がる（クロスフェード約20ms）。ブースト開始は350ms後。
- ローカル検証（当該作業ツリー）: `:shared` + `:common` unit **1196 PASS**、native DSPテスト PASS、`:mobile`/`:automotive` debugビルド PASS、app module `lintDebug` PASS、R8後JNIマッピング検証 PASS、`check_public_tree.py` PASS。ASan/UBSanはローカルMSYS2に `-lasan`/`-lubsan` が無く **BLOCKED_ENV**（CIで確認）。
- **未検証:** TS10/K706実機でのGPS速度精度・実際の音量変化・通話/ナビ/アシスタントへの非適用・バックグラウンド再生中の挙動・CPU負荷。リリース可と主張しない。
