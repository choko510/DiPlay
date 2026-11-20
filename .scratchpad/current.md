# 現在の状況 — オープンPR統合ブランチ（2026-11-20）

## 統合ブランチ `t3/integrate-open-prs`

- base は main `04c8818`。PR #80、#83、#79、#81、#82 をこの順でマージ（すべてDraft/未マージのPR、`main` へは未反映）。
- 衝突解決：`DspEqDesigner`/`DspRuntimeConfig`/`AndroidMediaSink`/native DSPテスト（#80×#83）、`CarPlayHostActivity`（#83×#81）、`DiPlayActivity`/`CarPlayUsbAttachActivity`（#81×#82）。`DiPlayActivity` は PR #82 の自動接続機械を採用し、PR #81 のフォーカス連携（`DiPlayUiFocus`、`showPage`、`navigateFrom`、保留要求の再開、`launchOrigin` によるUSB自動起動抑制）をその上に統合した。`initialLaunch` は廃止し `firstResume` に一本化。CH341アタッチは `CarPlayUsbAttachDispatcher` 経由に統一し、`onExternalSessionStart()` はディスパッチャの起動成功時に通知する。
- ローカル検証（統合HEAD）：`:shared` + `:common` unit 全PASS（`DiPlayActivityUsbAutoLaunchTest`含む）、native DSPテスト PASS（MSYS2 UCRT gcc）、`:mobile`/`:automotive` lint（`lintRelease`/`lintDebug`）・`assembleDebug` PASS。
- 統合時の追加修正：PR #82 の再構成で落ちていた「通知タップによる明示的USB起動」を `onResume` に復活（`pendingUsbLaunchFromNotification` をページ判定より優先）。
- **未実施：** 実機/ADBでの確認、API 35 instrumentation、Draftのままの再レビュー。マージ済みでもPRの未検証項目は解消していない。

## APK（認証資産入り、2026-11-20）

- 添付 `DiPlay-0.2.22.apk`（versionCode 41、release署名 CN=DiPlay）の `assets/offline-mfi/identity.pk8` と `certificate.p7b` を外部ディレクトリへ抽出して再利用。認証資産はGitに含めない（`.gitignore`対象外のパスには置かない）。ビルドには `DIPLAY_AUTH_ASSETS_DIR`（`offline-mfi`の親）を指定。
- `:mobile`/`:automotive` のdebug APK（arm64-v8a / armeabi-v7a / x86_64）を生成し、`assets/offline-mfi/` に両ファイルが入ることを確認。debugは `applicationIdSuffix=.hudtest` の別パッケージ・debug署名のため、既存の自動車側インストール（`com.shihab.diplay`）の更新にはならない。
- release署名は未実施：DiPlayのリリース鍵（`ANDROID_KEYSTORE_PATH` 等）が無く `validateSigningRelease` がfail closed。APKから復元できない。

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

## PR #82 — 自動接続信頼性（2026-11-20）

- 対象：[PR #82](https://github.com/choko510/DiPlay/pull/82) / `fix/auto-connect-reliability`。base `main` `04c8818`、Draft・未マージ。
- `AutoConnectCoordinator` で「DiPlayを開いたときに接続」の要求・重複排除・権限待機・上限付き再試行・明示的切断の抑制を一元化。`initialLaunch` の一回限り判定を廃止し、Activity再生成/`onNewIntent`/権限復帰で同じ起動キーを再利用する。
- レビューで指摘されたP1を修正：再試行上限/権限拒否は新しい起動キーでリセット、通知権限・Bluetooth権限ダイアログ中のActivity再生成でもtransport/世代/iPhone選択待ちを保持、自動接続OFF時の既存ホスト再試行を実行時に取消、初回iPhone選択後も自動要求世代を維持、OS設定で権限付与後の再起動でブロックを解除。
- 追加で、権限ダイアログ待機は20秒で失効させず、`startActivity`成功後は同一起動キーの二重起動を抑止。USB/手動開始は自動セッションの復旧抑制を解除。再試行は元要求のtransportを使い、USB自動起動中は無線再試行しない。
- 再レビューP1を追加修正：`onNewIntent`は新しいユーザー起動/BOOT/USB通知で終端状態（BLOCKED/EXHAUSTED）のときだけ新しい起動キーを発行し、内部設定遷移や再配送では維持。iPhone選択待ちを`WAITING_FOR_USER`として独立させ、Activity再生成後に選択ダイアログを復元。ホストの作成・セッション成立/終了・起動失敗をCoordinatorへ直接通知し、ホームのポーリング依存を解消。
- 3回目レビュー対応：選択キャンセル済みキーを記録して同じキーの再評価を拒否し、ランチャーからの新規起動だけ新しいキーで再開。iPhone選択の「別のiPhoneをペアリング」は要求を終了せず、設定から戻ると一覧を再取得して選択画面を復元。USB/手動への所有権移行では自動接続世代を無効化し、ホスト側もUSB Intentで古い世代を破棄。プロセス死亡で残った権限待ち世代は、自動接続設定がONなら新しい要求として復旧。セッション未成立のホスト終了も起動失敗として通知。
- 4回目レビュー対応：`onResume()`前に届いた古い権限結果を検出し、古い要求を再実行せず`onResume()`で新しい起動キーの要求として復旧。ホストは自動接続世代が現在の`STARTING`要求と一致しない限り起動せず、`onNewIntent`の古い世代も既存ホストを終了させない。セッション未成立のホスト終了判定を`hasSession()`ではなく実際のAirPlayセッション有無で行い、Controllerだけが残る状態も起動失敗として処理。
- `BOOT_COMPLETED` は起動前に通知を用意し、Activity起動後に取消すフォールバックへ変更。Android/OEMが例外なくActivity起動を拒否してもユーザー操作を残す。
- 自動開始セッションに限り、自動接続OFF・ユーザー切断・必須権限拒否・試行上限で `CarPlayHostActivity.reconnectAfterLoss()` を抑制。手動/USBセッションの既存復旧は維持。
- Bluetooth監視・自動ペアリング・自動接続は追加していない。手動iPhone選択と既存RFCOMM経路は不変。
- ローカル確認：shared unit **756/756 PASS**、common unit **460/460 PASS**、mobile/automotive lint・Debug build・Benchmark build PASS。
- **未検証：** TS10/K706（Android 10）実機のBOOT起動制限・通知タップ、Android 13+通知権限、Bluetooth/無線権限拒否、Activity再生成、USB+無線競合、実CarPlay接続。モック成功を実機接続成功と扱わない。
- **CI未実行：** `workflow_dispatch` は `HTTP 422: Actions has been disabled for this repository` で拒否。API上は `enabled=true` / workflow activeだが外部制約。ローカルCI相当はPASS済み。


## PR #45（2026-11-05）

- 対象：[PR #45](https://github.com/choko510/DiPlay/pull/45) / `codex/step4-pr-00-test-baseline`。PRはopen・未マージ。最新の受入れ状況は [backlog.md](backlog.md) とGit履歴を参照し、本節は履歴の要約として扱う。

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

次の作業は [backlog.md](backlog.md)、壊してはいけない仕様は [decisions.md](decisions.md) を参照。
