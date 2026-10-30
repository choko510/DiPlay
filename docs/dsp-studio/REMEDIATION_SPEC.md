# DiPlay PR #52 — DSP Studio 全機能完成・欠陥修正・独立レビュー 最終実装指令書

> **CodexにこのMDをそのまま渡して実行すること。** 調査で終了してはならない。PR #52を修正し、Phase 01–05、音質マクロ、Smart Loudness、Dynamic Bass 2.0、Phase 07 Live DSP、および既存回帰を、実コード・実テスト・証拠で完成させるためのタスク指令である。本文内のファイル位置は確認済みHEADを基準とするが、実装着手時に最新コード・最新CIを再照合しなければならない。
>
> **作成:** 2026-10-26 JST
> **対象:** <https://github.com/choko510/DiPlay/pull/52>
> **確認時HEAD:** `ba6b65925b4b3ed84c51407caa9e4cc3de18d4be`
> **対象headブランチ:** `codex/dsp-studio-final`
> **baseブランチ:** `codex/step4-pr-00-test-baseline` — PR #45のhead（基準時`ac4694cf1fbb08e443f53a53158528619ed81940`）
> **コードレビュー根拠:** 既存の60変更ファイル、今回の公開HEADと直近GitHub Actionsログを参照。現在HEADの実機テスト成功を主張する文書ではない。
>
> **成果物の形:** コード、レビューしやすい分割コミット、対象PR #52へのPush、Phase・REQ・テストのID追跡台帳、CI証拠、TS10/iPhone検証Runbook、4分野の独立レビュー結果。**既存PR #45、PR #52、mainを自動マージしないこと。**

---

## A. Codexへの絶対GOAL（これが第一優先命令）

あなたはDiPlayのDSP Studioを完成させる**実装責任者**である。本文を最初に全部読み、必要なコードとテストを実際に変更してPR #52を完成させる。調査報告、計画、スクリーンショット、モック画面、局所テストだけで完了にしない。途中の未達を隠さず、**実装できるものは実装し、検証できるものは検証し、環境がない試験だけを明確にNOT_RUNにする。**

1. まずブランチ、HEAD、PR base、未コミット変更、最新CI、AGENTS.md、`.scratchpad`、`docs/dsp-studio`を読む。
2. 元の実装とテストを壊さず、**本書のCR項目、P1/P2指摘、正式T-IDすべて**をタスク台帳へ登録。
3. 初期FAILを再現、実コード修正、Host Unit、Native、Android Instrumentedで回帰テストを実行。
4. Phase 01 → 02 → 03 → 04 → 05を使える製品として完成させる。UIの『名前だけ』や『表示だけ』を禁じる。
5. 新3機能（Macros、Loudness、Dynamic Bass）のUI/Native統合を修復し、既存DSPへ安全なLive Updateを拡張する。
6. 音声品質・スレッド安全・保存整合性・CarPlay/USB/Surface所有権を破壊しない。不能/未証明のstateful切替は正しくDEFERREDにし、**未実装を実装済みと偽装しない**。
7. 詳細テスト台帳と独立レビューを通過するまで修正→検証を反復。**ユーザー判断前にPRはmergeしない**。
8. 最終結果は、PR #52の最新commit、分割コミット、各Phaseの合否、未実施項目、残存欠陥、CI run URL、実機Runbookを必ず報告。

**禁止:** `main`やPR #45への直接Push、無断force-push・履歴改変、無関係なUSB/AirPlay変更、テストskip/削除によるGREEN偽装、CIが赤のまま`MERGE_READY`宣言、端末不在の実機PASS捏造、録音や識別IDをログへ垂れ流すこと。

**進め方:** 独立した修正はレビュー可能な小コミット/子PRに分離。Luna subagentが実環境に存在し利用可能なら独立したUI、プリセット、EQ、セッション、Nativeテスト担当をLunaへ委任し、worktreeを分離する。実際の機能とモデル識別子・構成方式をCodex環境で調べ、未対応なら`AGENT_UNAVAILABLE`としてCodex自身で担当する（Lunaを利用したと偽称しない）。既存子PR #53–#58はマージ済みであるため、ユーザー承認なしにこれらを書き換えない。新規子PRを作るなら**baseは`codex/dsp-studio-final`**、必ず内容をレビューしてからPR #52のheadへ統合。既存PR #52のheadが作業中に進んだらfetch/比較、conflict解決、テストを再実行。

---

## B. 現状の確認事実と不具合インベントリ（実装前に再確認）

### B1. ベースライン（変化するリポジトリに対応）

- PR #52: <https://github.com/choko510/DiPlay/pull/52>、基準HEAD `ba6b65925b4b3ed84c51407caa9e4cc3de18d4be`。旧比較HEAD `9cab10296c665824b9470de52b37d0a05f536db1` → `a0b197a9b876aa0fd12c14b805cf8b3ce1c6a4a7` は主にAndroid UIテストのみ、さらに`ba6b6592`は画面側の小変更。**着手時に絶対に最新差分を確認する**。
- 既存PR #45 headは`codex/step4-pr-00-test-baseline`。PR #52のbaseがこれであることを常に確認。`main`から差分評価するだけではPR #45の先行実装も混じる。
- 2026-10-26のHEAD `a0b197a9`でのGitHub Actions <https://github.com/choko510/DiPlay/actions/runs/40249136921>はFAIL。`DspSettingsScreenTest.kt:64` `assertExists()`失敗、Native heavy `DspPipelineBenchmarkInstrumentedTest.benchmarks48000Mono`でConvolver64k p99 `30.477ms` > `21.332ms`。後者が本修正による回帰かエミュレーター揺らぎかは未確定。**原因分離し、性能ゲートを勝手に緩めない。**
- `ba6b6592`のActions #40250193181は**completed/failure**。UI testの先行assert失敗はログ上再出現せず、代わりにNative/Renderer性能検証で48kHz stereo FullHeavyChain p99=34.925ms、mono PEQ+Compressor+Limiter p99=28.297msが閾値21.332msを超過。性能変動か回帰かは未確定。Codexは着手時にさらに最新のCI結果を取得して更新。
- 公式元仕様：`DiPlay_PR45_Codex_Final_DSP_Studio_Macro_Loudness_DynamicBass_2026-10-26.md`、原調査：`DiPlay_PR45_DSP_UI_Redesign_Implementation_Plan_2026-10-25.md`、`DiPlay_PR45_DSP_UI_Module_Presets_Deep_Investigation_2026-10-25.md`、`DiPlay_PR45_DSP_Phase07_Integrated_Live_Engine_Research_2026-10-26.md`。**これらが作業環境に無くても本MDは単体で実行できる。**

### B2. 修正が必要な既知項目（修正対象、確定原因と未確定原因を区別）

| ID | 深刻度 | 確認済みの状態/疑われるリスク | 受入ゲート |
|---|---|---|---|
| CR-001 | P1 | HEADのUI instrumentationテストが失敗。前HEADはpreview反映待ちタイムアウト、新HEADはUnsaved表示のassert失敗。原因確定まではTimeout延長で逃げない | UI実行PASS、race再現テスト追加 |
| CR-002 | P1 | 4マクロSlider→UI stateのみ。音声反映には手動Previewボタンが必要 | ドラッグ中自動音声反映 + pointer-up flush |
| CR-003 | P1 | Runtimeの音量Source初期値`ManualVolumeSource()`。Smart Loudnessの本番AUTO読取り実装が配線されていない | sourceを実Rendererに接続、信頼できないOEMはAUTO_UNAVAILABLE |
| CR-004 | P1 | `studioVolumeReading()`呼出しがDSP UI可視時のpoll中心で、Close後に音量追従しない | Renderer/Playback ownerへ移管 |
| CR-005 | P1 | `DspSettingsScreen`の`remember(runtime){createStudioSessionCoordinator()}`でActivity再生成時の未保存Previewが旧committedから再構築されうる | Coordinator所有権、recreation/process-death E2E |
| CR-006 | P1 | `DspProfileRuntime.init`で保存Workspace `profileSourceId != selectedId`を黙って除外 | 保存済みWorkspace復元・profile切替契約 |
| CR-007 | P1仕様差分 | `dsp_graph.c`のDynamic Bass検出は既存Compressor/IR後の入力。元PCM/preboost検出契約と異なる | 検出tap/順序、音質/位相/レイテンシ試験 |
| CR-008 | P1機能未達 | ナビは`Studio/Profile/EQ/More`の4種。要求`Studio/EQ/Spatial/Dynamics/Advanced`未達 | 実際に5タブ化、全旧機能の移動 |
| CR-009 | P1機能未達 | EQ Canvasは描画専用、pointer/drag/numeric未実装。PEQカード15枚縦積み | touch drag/number/Q/type, 横幅対応 |
| CR-010 | P1機能未達 | 対象モジュールだけで比較するA/B、Level Match、per-module Resetなし | field-scoped比較、誤ラベルなし |
| CR-011 | P2 | User Preset delete/Factory Reset、IRの全参照保護なし | 削除API/UI、IR集合参照 |
| CR-012 | P1 | 既存DSPのHotは`MACRO_TONE/SMART_LOUDNESS/DYNAMIC_BASS`のみ許可 | PREAMP・PEQ・既存DSPへsafe live化 |
| CR-013 | P1 | `DspUpdatePlanner.classify`は目標Graphの`requiresStateContinuity`による大域DEFERでPEQ変更も延期される | field-local classification + stage WARM |
| CR-014 | P2 | Factory Macroセットが要求6種未満、Macro専用Preset導線不足 | 指定stable IDs・Factory/User UI |
| CR-015 | P2 | EQグラフを44,100Hz固定表示、合成Macro/Loudness/DynamicBassを実Native特性と合致させていない | 実PCM rate＋Native transfer一致/注記 |
| CR-016 | P1品質 | DSP保存SucceededとRenderer ACTIVEが混在しうる。NO_MEDIA状態、DEFER理由、owner世代表示不足 | 独立status、ACKでACTIVEのみ |
| CR-017 | P1品質 | Native UI kill switches (`DspStudioFeatureFlags`)がすべて`false`定数の未接続 | 実効feature flags、fail-safe rollout |
| CR-018 | P1品質 | test matrixの`T-xxx`一括AUTOMATED_PASSに対し、1件ずつの証拠が足りない | 110+個別T-ID x command x SHA x evidence |
| CR-019 | P2 | `DspStudioSessionCoordinator.edit()`は1回呼出しでundo stackに積むためスライダー1ドラッグで多数操作の恐れ | transaction-based undo/redo |
| CR-020 | P1 | Hot update Nativeのgeneration/owner/ack/close/rebind/graph retirementの競合・多producer前提を未網羅 | ThreadSanitizer等可能範囲+Native stress/E2E |
| CR-021 | P1 | Stage hot updateのHeadroom計算がC 1-pole stageとKotlin近似EQ設計で不一致になり得る | 実装一致/保守的上限/limit試験 |
| CR-022 | P1 | コマンドHot送信と画面ドラッグ/Cancel/Saveの整合性がなく、古いACKが新しい編集へ届く危険 | epoch+generation+snapshot一致、Last-good維持 |
| CR-023 | P1 | Convolver64k performance gate FAIL。負荷回帰はまだ断定できない | reproducible benchmarkと基準採否 |
| CR-024 | P1検証 | TS10/K706/iPhoneの実機Split20回、音質、Siri、call、CPU、長時間未実施 | HARDWARE_NOT_RUNでmerge NO_GO |

### B3. 確認済み主要ファイル（変更を始める前に再度読む）

| パス | 既存の役割と主な問題 |
|---|---|
| `common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt` | 1,596行級の巨大Compose画面。4タブ、旧Advancedを含み、`SideEffect`でUI stateをsessionへコピーし、Preview手動。**責務分割対象。** |
| `common/src/main/java/com/shilapi/xcertplay/DspMacroStudio.kt` | 4スライダーとMacro Power。ユーザー入力が直接DSPへ届かない。 |
| `common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorPanes.kt` | `DspEqEditorPane`, `EqResponseGraph`、`DspAdvancedControlsPane`、`DspModulePresetPane`。後者の多くが全体画面へ実利用されていない／情報設計不足。 |
| `common/src/main/java/com/shilapi/xcertplay/dsp/DspProfileRuntime.kt` | preview/save/restore、studio owner、volume source、module repository。UI stateとRuntime stateの所有権の不整合を解消。 |
| `common/src/main/java/com/shilapi/xcertplay/dsp/DspStudioPersistence.kt` | schema v1 workspace、14moduleのAtomicFile、undo/A-B。削除、IR GC、新session lifecycleが未完。 |
| `common/src/main/java/com/shilapi/xcertplay/dsp/DspStudioSettings.kt` | Workspace/Macro/Loudness/DynamicBass型と未接続feature flags。 |
| `common/src/main/java/com/shilapi/xcertplay/dsp/DspTuningResolver.kt` | Macro曲線、6dB追加低域budget、AUTO/MANUAL、Headroom連動。 |
| `common/src/main/java/com/shilapi/xcertplay/dsp/DspVolumeSource.kt` | Manual/Fixed/Android lambda adapter、校正。真のAndroid source・owner連携を追加。 |
| `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` | Audio側Graph採用、Hot/ACK、WholeGraph crossfade、latency、renderer lifecycle。変更は最小の責務分離で。 |
| `shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspRuntimeConfig.kt` | `canApplyStudioHotUpdateTo`が新3機能しか許可しない。 |
| `shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt` | 3way Plannerはあるが、statefulグラフ全体で延期。 |
| `shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspEqDesigner.kt` | PEQ・Headroom・Native準備。実特性誤差、Stage新旧合成と静的ピーク評価。 |
| `shared/src/main/java/com/shilapi/xcertplay/media/dsp/NativeDspProcessor.kt` | Native engine handle、JNI Hot command bind、ACKとlease境界。 |
| `shared/src/main/jni/dsp/dsp_engine.c`, `dsp_graph.c`, `dsp_jni.c` | mailbox/owner token、Native Stage graph、JNI配置。Audio callback Lock/alloc禁止。 |
| `shared/src/main/jni/dsp/dsp_macro.c`, `dsp_smart_loudness.c`, `dsp_dynamic_bass.c` | 新3処理段。LPF/EQと検出Tap・smoothing・CPUを評価。 |
| `common/src/androidTest/java/com/shilapi/xcertplay/DspSettingsScreenTest.kt` | 現在CIで失敗中。単純にassertやTest自体を削らない。 |
| `shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspRendererProductionPathTest.kt` | 旧/新Render状態、AudioTrack、latency・安全性。 |
| `docs/dsp-studio/TEST_MATRIX.md`,`REVIEW_MATRIX.md`,`RUNBOOK.md` | 実際の各test、課題/証拠/リスクを個別に記録する。 |

全修正において、最初に`rg -n`で最新のsymbol/file位置を取得し、**本書に書いた行番号や旧HEADを信じて既存コードを上書きしない。**

---

## C. 必須の実装アーキテクチャ / 不変条件（フェーズ共通）

### C1. Single Source of Truth — 7つの独立状態

`DspStudioCoordinator`（Runtime/Activityスコープで所有、Compose画面の生存に依存しない）で以下を**必ず明示区別**する。

1. `persistedWorkspace`: AtomicFile保存済み設定のimmutable snapshot。ユーザーSaveが成功した場合のみ置き換える。
2. `draftWorkspace`: UIが編集中の設定。画面ローカルだけではなくCoordinatorが保存・復元する。**Process death後に未保存値を永続化するか破棄するかを一意に定義**（初版は破棄、UI明示）。
3. `requestedWorkspace`（+ `requestId`, `editRevision`, `editorOwnerEpoch`）: Audioへ実際にpublishした設定。
4. `preparedRendererConfig`（+ `nativeHandle`, `rendererOwnerToken`, `pcmFormatEpoch`）: Control workerで構築/検証済み。
5. `activeRendererConfig`（+ block-ack generation）: **当該Audio blockで出力に使われた**と証明された設定。`DspConfigProvider.snapshot()`が新しいだけではACTIVEとは呼ばない。
6. `comparisonSnapshot`（A/B scope、currentSide、temporaryLevelMatchGain、比較の元immutable state）。全体保存値とは別。
7. `saveStatus`: `CLEAN/DIRTY/SAVING/SAVED/WRITE_FAILED/FUTURE_SCHEMA`。renderer stateと混合しない。

**不変式:** `persisted`変更は成功したCommit経由のみ。`active`更新はAudio ACK経由のみ。`requestGeneration`は単調増加、Cancel/Undo/比較戻しも**新しい世代で再要求**。古いasyncコールバックは同一Editor owner+Revision+Renderer owner+PCM format+Generationが一致するときのみ採用。UI previewとpersistedの交差上書き不可。

`DspSettingsScreen`は入力と表示に徹する。Composableの`SideEffect`で一方的にsessionへ全Workspaceを書き戻してUndoを増やす設計をやめ、明示的なaction/reducerで編集を処理。`rememberSaveable`はタブ/スクロール/選択中帯域等のUI-only stateに使い、保存/Audio ownerにしない。

### C2. 編集トランザクション

- 一回のドラッグを`EditTransaction(id,module,field,before,after)`として扱う。pointer-down開始、連続変更はdraftのsame transaction、pointer-up/Cancel/フォーカス喪失で確定。Undo履歴は**drag全体で1件**。数値確定/プリセット選択/Factory Resetは各1件。
- Undo/Redoはdataset復元だけでは不十分。**Audio Preview requestを再発行**し、ACKを世代付きで待つ。Redo履歴クリアは新規編集時のみ。
- 非同期Preset load/Save、IR import、volume updateが画面と違うprofile/moduleへ適用されるのを防ぐowner/selection identity fence。
- 入力値は必ずCompose表示上で即更新。Audio側は最終候補をcontrol executorへ頻度制限（推奨30～70ms試行、実機最適化）。pointer-upのfinal flushと保存/取消しは**最終候補を必ず送る**。UIスレッドをNative処理で塞がない。

### C3. Audio Graph更新契約

- `MEDIA` roleだけDSPのフル処理。Siri/Call/Nav/voice messagesは既存stream classificationを変えない。
- `HOT`：同一固定topology/同一latency、処理段がブロック境界でatomic stagingされたparamを取り込む。Audioコールバック中のmalloc/free、I/O、lock待ち、Java API、JNI配列生成禁止。Stageのfilter coefficient/interpolationは状態連続性と安定性を検証する。
- `WARM_LOCAL`：対象Stageのみ新しいfilter/stateをControl threadで準備→Stage適切な入力履歴を暖機→同一latency保証のうえcrossfade→旧stateを非音声Reaperで廃棄。他モジュールのCompressor/IR/Delayはresetしない。
- `WARM_GRAPH`：局所warmが未対応であり、旧/新Graphを並走させる場合に限定。phase/latency/状態履歴の差がある場合は安全の証明が必要。根拠が無ければ次の`DEFERRED`にする。
- `DEFERRED`：IR size/quality/algorithmic delayの変更など、安全なmid-track移行を証明できない場合。現行音声を壊さず、理由・現行/要求generation・実反映予定をUIに表示。**敢えてDeferredにした箇所を隠して『完全ライブ』と宣言しない。**
- `FAILED`：invalid config, stale owner, prepare error, unsupported format, ACK timeout, Native failure。Last known goodを維持/復元し、理由と復旧導線を表示。

### C4. Renderer Status/Audio ACKの厳密性

`LOCAL_EDIT → REQUESTED → PREPARING → PREPARED → RAMPING/CROSSFADING → ACTIVE`が通常の経路。`DEFERRED`、`FAILED`、`NO_MEDIA`、`UNSUPPORTED_FORMAT`等も別扱い。`SAVED`は永続化の結果であり、ACTIVEに従属しない。旧`DspRendererApplyPhase`enum拡張で互換を壊すより、UI集約Stateに追加で表現可能。

**ACTIVE判定はPCM処理に実際に当該versionを使用したAudio block完了後のACKでのみ**。`dsp_engine_enqueue_hot_update()==1`はenqueue成功でありACTIVEではない。処理が停止/メディア無しなら`NO_MEDIA / WAITING`であり、UIでACTIVEと偽装しない。Render role/format/generation/ownerが古くなったACKは無視。A/BとCancelは通常requestと競合してよいがlatest-wins/rollbackに矛盾しない設計。

### C5. 旧DSP・CarPlay不変条件

- `Master OFF` + **新AudioTrack世代**の旧PCM16はbit-exact。再生途中OFFは既存latency-preserving bypassの契約を尊重。
- 新3機能OFF/Neutralでは旧Factory音質/Headroom/lagと一致するgoldenテスト。
- 15本のPEQ+承認Cabin Notchの**有効合計≤15**。DynamicEQ(最大5)やMacro stageはこの15枠を**勝手に消費しない**。
- Convolver IRのmismatch、欠損、mono/stereo、44.1k/48k、PCM8/16/24/float等の既存エラー/遅延契約を尊重。
- Split 55:45のUI変更時に`AirPlaySession`/`MediaCodec`/`TextureView`/CarPlay Surface/Host TCP/USB NCM/iAP2/AudioTrackを再接続・再生成しない。左右タッチ座標一致、回転/Activity再生成、Siri/通話/ナビの音声role保護。
- 必ずFeatureFlagの実効経路と緊急OFF。Master OFFだけに頼らず『新DSP Studio UIだけ旧画面へ退避』『Macro stage OFF』『新HOT経路OFF』を選べるようにし、旧データへ戻せる。

---

## D. Phase 01 完遂：モジュール別プリセットの基盤と保存処理

**既存状態:** `DspModulePresetRepository`/`DspStudioWorkspaceRepository`、AtomicFile、14 moduleKey、Factory＋User、Scoped Apply、User Save、future schema拒否が実装済み。**既存を捨てて再実装するな**。不足を埋め、後方互換を維持する。

### D1. 最終仕様（PH01-01～PH01-15）

- `PH01-01`: Supported keyは `peq,bass,seat,advanced_ms,compressor,multiband,dynamic_eq,harmonic,limiter,convolver,cabin,smart_loudness,dynamic_bass,macros` の14個。すべてFactory+Userの表示、選択、適用、保存、削除（Factoryは削除不可）を提供。IDは安定、ユーザー名は48文字以下、重複名の許容/拒否方針を明示。
- `PH01-02`: `PresetScope`をsealed enum/型で表現。単に文字列keyを信頼せず、JSON codecをmodule別に検証。出所`FACTORY/USER`、`schemaVersion`, `payloadVersion`, `id`, `moduleKey`, `name`等の不変条件を厳格に確認。
- `PH01-03`: `applyModulePreset(workspace, preset)`は**指定scopeだけの差し替え**。構造体等価でその他scopeはbitwise/structural equality。Factory値を後からeditで変更するのは禁止。
- `PH01-04`: `UserPreset save`は`AtomicFile.finishWrite`成功後だけ`SAVED`。失敗時は`.bak`回復、元ファイル維持、UIでFAILED。Async callbackはmodule ID/Editor epoch/Workspace revision/Session一致を要求。
- `PH01-05`: **User Preset削除機能を追加**。repository `deleteUserPreset(moduleKey, presetId)` とRuntime async/Compose dialog。Factoryやfuture-schema未知ファイルは決して削除しない。Presetが現在選択中ならselected IDだけ解除し、他のDSP数値は勝手に変更しない。
- `PH01-06`: **Factory Reset**。各moduleに「Factory Neutralへ戻す」操作、明示確認（Seat polarity, IR, Limiterなど危険操作）。Undo 1操作。モジュール外データ不変。全体Factory resetと明確に区別。
- `PH01-07`: IR reference setに active pipeline, persisted workspace, draft, A/B cached snapshots, custom1/custom2, user module convolver presets, IR import operationsを含める。単一`DspImpulseReferenceRegistry`/repository helperを作成。削除はsnapshot/lockまたは安全なserialized workerで参照検査→削除を同一シリアライズ領域で行う。IR欠損時はプリセットファイルを破壊せず`INVALID_REFERENCE`と再選択/IR Importへ誘導。
- `PH01-08`: Presetのcapacity: PEQ+Cabin ≤15を**適用前**に確認。例えば現行Cabin=2+Preset PEQ=14は拒否する。既存PEQ/Cabinを黙って削らない。Dynamic EQ≤5も検査。
- `PH01-09`: A/B/Undoとの連携はmodule ID+fieldScopeベース。Factory→手動編集時は`Custom (unsaved)`状態、Factoryへの再選択で元Factory値の再ロード。User保存はFactoryファイルを書き換えない。
- `PH01-10`: 未来schema `99`やパーサ不明enumでfail closed、**読み込めないファイルを除外したまま上書きしない**。UIで「現在のアプリで編集不可」を通知。サイズ・個数上限を維持。JSON numbersはNaN/Infinity/overflow拒否。
- `PH01-11`: path traversal防止：`..`, `/`, `\\`, `%2f`, Unicode confusables、正規化、symlink（権限内可能なら）を検証。user入力をディレクトリ名/直接pathに連結しない。
- `PH01-12`: v7 Profile storage (`custom1`,`custom2`, Factory profile)は読み書き既存意味そのまま。Workspace schema=1の独立sidecar案を維持。UserがSaveしていない限りworkspaceを自動生成しない。
- `PH01-13`: **Profile切替/起動時のsource mismatch契約**。現行`DspProfileRuntime.kt` `init`で`workspace.profileSourceId != selectedId`を無通知で無視する仕様を修正。推奨：`persistedWorkspace`を独立永続基準とし、旧Profile選択時はWorkspace draftとして選択するか、その操作を明示的にSave/Discardする。再起動時は最後に明示保存したWorkspaceが必ず復元可能であること。旧旧選択IDは互換移行として扱い、同時更新による二重SoTを避ける。仕様選定を`docs/dsp-studio/STATE_POLICY.md`に記録。
- `PH01-14`: 電源断/プロセス落ち相当のAtomicFileテスト、プロファイル切替→再起動、`selectedModulePresetIds`の孤立参照テスト。
- `PH01-15`: Module個別Presetの保存・削除はAudio Graph/Native engineを直接変更しない。**適用した時**だけdraft変更、previewが必要ならPhase04のsafe pathで音に送る。

### D2. 推奨ファイルとインターフェース

```kotlin
// 参考設計。実プロジェクトのアクセス制御・既存型に合わせて置き換える。
interface ModulePresetActions {
    suspend fun list(module: ModuleKey): List<ModulePresetDescriptor>
    suspend fun saveFromDraft(module: ModuleKey, name: String): SaveResult
    suspend fun deleteUser(module: ModuleKey, id: PresetId): DeleteResult
    fun previewApply(module: ModuleKey, id: PresetId): EditResult
    fun resetModuleToFactory(module: ModuleKey): EditResult
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object ProtectedFactory : DeleteResult
    data object FutureSchema : DeleteResult
    data object ReferencedOrBusy : DeleteResult
    data class Error(val safeCode: String) : DeleteResult
}
```

対象：`DspStudioPersistence.kt`, `DspProfileRuntime.kt`, `DspEditorPanes.kt`, 新`DspImpulseReferenceRegistry.kt`（必要時）, `DspStudioPersistenceTest.kt`, `DspModulePresetPaneTest.kt`, `DspProfileRuntimeTest.kt`。

### D3. 個別のテストDoD

`PH01-T01`: 14 keys× Factory選択→変更→Save→load→Scoped apply。`PH01-T02`: 他13 module不変をチェック。`PH01-T03`: Future schemaを保持、Factory delete拒否。`PH01-T04`: User deleteと再選択。`PH01-T05`: 16+1 band違反拒否。`PH01-T06`: IRを参照する複数Preset作成中/削除競合/欠損。`PH01-T07`: I/O exceptionとpartial JSON。`PH01-T08`: `flat Save → profile vocal → restart`が期待状態を復元できる。`PH01-T09`: Module Save中Activity破棄/選択変更で別moduleへ結果を付けない。`PH01-T10`: v7 goldens unchanged。

**Phase01 完了条件:** `PH01-T01..10`全PASS、User DeleteとFactory Resetが実UIからアクセス可能、旧データ保持と全モジュール局所性を証明。**完成前にPhase01 DONEと書かない。**

---

## E. Phase 02 完遂：DSP Studio UI全面刷新・5タブ・Split最適化

**既存状態:** `DspSettingsScreen.kt`の`Studio/Profile/EQ/More`4タブ、巨大`Column(verticalScroll)`。`DspAdvancedControlsPane`など新Paneはあるが統合不足。**古い大量スライダーをタブ名だけ変えて残すことは禁止。**

### E1. 5タブ情報設計を確定する

| タブ | 主機能 | 常にアクセスできる操作 | 細部展開 |
|---|---|---|---|
| `Studio` | 4 Macro、Macro factory/user chips、Smart Loudness状態、Dynamic Bass状態、ライブ試聴・保存・A/B | 4スライダー、Preset、Undo、Active/Saved status | Advanced Macro Transfer、Auto校正導線 |
| `EQ` | 15-band parametric EQ + Cabin capacity、設計カーブ、Num edit、Band Type、Preamp | Graph、選択Band F/G/Q、Enable、Factory/User EQ Preset | 全Band表、Cabin overlay、Headroom内訳 |
| `Spatial` | Seat Alignment、Legacy Width/Mono Bass、Advanced M/S、Phase/Delay注意 | Module card + current values +Preset/A-B | Distance、polarity、crossover、side comp |
| `Dynamics` | Compressor、Multiband、Dynamic EQ、Limiter、Smart Loudness、Dynamic Bass | module strip/presets/active/overload | threshold/ratio/attack/release/boost/limiter GR |
| `Advanced` | Harmonic Engine、Convolver IR、Cabin Correction、Import/Export、legacy profile管理、Diagnostics、Feature flags / Calibration | IR status、Diagnostics、Factory/User modules | file import、IR rate mismatch、詳細保存・Log導線 |

既存の`Profile`/`Basic`は**内容を失わず**`Studio`または`Advanced`へ移す。Legacyユーザー操作の後方互換：旧画面で設定できた全フィールドを列挙した`docs/dsp-studio/LEGACY_FIELD_COVERAGE.md`を作成し、各fieldの新画面・toggle/number/preset導線を1対1記録。複数画面で同じ変数を異なるDraftに保持することを禁じる。

### E2. Compose分割と画面契約

推奨ディレクトリ（実際のプロジェクト規約に合わせてもよいが同一責務の二重実装を避ける）：

```text
common/src/main/java/com/shilapi/xcertplay/dsp/ui/
  DspStudioRoute.kt             // Host screen / navigation shell only
  DspStudioViewModel.kt         // Coordinatorの表示状態とEdit actions
  DspStudioDesignSystem.kt      // sizes, spacing, touch, typography
  StudioTab.kt
  EqTab.kt
  SpatialTab.kt
  DynamicsTab.kt
  AdvancedTab.kt
  ModuleCard.kt                 // shared ModuleChrome & summary
  ModulePresetControls.kt
  DspApplyStatusBar.kt
  DspComparisonBar.kt
  DspNumericalEditor.kt
  DspStudioSemantics.kt
```

- `DspSettingsScreen` public/internal entrypointは既存呼出し互換の薄いshellに。巨大switch/formatter/DB処理をComposableへ混在させない。`DspAdvancedControlsPane`の実際の用途を統合し、旧Editorとダブル状態にしない。
- `Tab`/`Presentation`はUI専用で`rememberSaveable`。`Draft`, `Preview`, `Undo`, `Loaded presets`, `Volume Telemetry`, `Audio generation`はSession-scoped CoordinatorまたはViewModel stateで持ち、ComposeView再生成で失われない。
- `LazyColumn`と`rememberSaveable` scroll anchors、`AnimatedVisibility`等を適切に利用。コンポーネントの`key(moduleId)`で再配置時も編集中Band selectionを維持。タブ切替でMedia graphは触らない。
- `ModuleCard`には必ずモジュールのON/OFF、Factory/User preset selector、Apply/Save/Delete、Factory Reset、Scoped A/B、Undo、`Custom (unsaved)`、`LOCAL_EDIT/REQUESTED/ACTIVE/DEFERRED/FAILED/NO_MEDIA`状態を提供。ただし誤タップ防止のため危険操作はconfirm。
- 既存Advanced M/Sとlegacy Widthの重複操作を分かりやすく表示。相対Macro Widthも合成内訳として表示し、「全てONにすると強制2倍幅」等の意図しない意味変化を防ぐ。
- 旧v7 Factory/Custom 1/2を可視化し、保存済みWorkspaceとの区別を表示。Save Workspace/Save Module Preset/Save Legacy Custom Profileの違いをUIラベルに明記。

### E3. FULL/COMPACT_SPLITの実装値と受入画面

- 実寸ではなく**与えられたPane制約**を基準に、Compose `BoxWithConstraints`/WindowSizeClass相当の実幅でレイアウトを選択。狭い45%Paneではタブをコンパクトな横スクロールタブ、またはセグメント＋メニューへ折り畳み。操作ミスが起きないようラベル/アクセシビリティを維持。
- 典型的45% SplitでStudioの4 Macroをできるだけ**1ページ内**へ：各Macro行の値、スライダー、±微調整、Preset、Undo/Saveを優先。他のLoudness/DBの詳細は折り畳む。車載で延々15 bandをスクロールするUI禁止。
- 高さInfinityのOEM Surfaceも考慮し、min/maxHeightだけでなく`LazyColumn`の測定constraintをテスト。`Modifier.fillMaxSize`の重複やScaffold top/bottom計測によるunbounded/二重paddingを防ぐ。
- タッチtargetは適正サイズを確保（目安48dp/Androidアクセシビリティ基準）、文字スケール1.0/1.3/1.5、左右横長/縦長、on-screen keyboard、Touch/rotary/外付けkeyを試験。
- トーン/幅をいじっても`CarPlayHostActivity`のpane weightだけの変更原則を維持。CarPlay Surface/Session/Decoderを再作成しない。`DspSettingsScreen`再生成→Touch座標校正差分を検査。
- 運転中の高度な精密編集を推奨しない。停車時詳細操作を主用途とし、移動中はPreset等の単純操作導線に限定する検討を記録。

### E4. UI品質とテスト

`PH02-T01`: ナビは5タブ、Back/Restore、旧全機能へ到達。`PH02-T02`: Tab切替100回でDraft・Band Selection・Scroll位置維持。`PH02-T03`: 45%幅、無限高さ、縮小画面でクラッシュ/操作不能なし。`PH02-T04`: large font・IME・TalkBack・外部key。`PH02-T05`: Host Split 20往復でsession/Surface/decoder identity一致。`PH02-T06`: 旧Profile/Basic/Advancedすべて同じ数値へ到達。`PH02-T07`: Composeスクロール性能、過剰recompose/alloc測定。

**Phase02完了条件:** Studio/EQ/Spatial/Dynamics/Advancedの**実画面**が本番起動し、旧4タブに依存しない。`LEGACY_FIELD_COVERAGE.md`の全fieldが新UIに対応。FULL/Compact両方をinstrumented/スクリーンショットで検証。CarPlay/実機部分は未実施ならHARDWARE_NOT_RUN、merge不可。

---

## F. Phase 03 完遂：直接操作できる15バンドグラフィカルEQ

**既存状態:** `DspEditorPanes.kt`の`EqResponseGraph`はCanvas描画だけ。全Bandは縦Card+Slider、サンプルレートは本番画面から`44_100`を固定渡し。`dspEqResponseDb()`は計算グラフの一部だが、MacroはCの1-poleステージ、Static eqモデルはRBJ biquad等の差がある。

### F1. 表示とグラフの厳密な仕様

- X軸対数 20Hz–`min(20000, sampleRateHz*0.45)`、Y軸dB。周波数グリッドは20, 50, 100, 200, 500, 1k, 2k, 5k, 10k, 20k(上限次第)、gainは−18..+18dBで表示範囲を必要に応じて適切に変更。低サンプルレートではNyquist条件に沿ってband handleをclampし、**実際の保存/PCMには暗黙にclampした値を勝手に永続化しない**。
- 既存PEQ 15 + Cabin approvedのcapacity共有を示し、各BandのEnable/Disable、Filter Type、Q、有効Bandの色/選択状態、Cabinノッチ（編集権限の区別）を表示。
- Frequency responseは**predicted/static**とラベル付け。`input spectrum/FFT`の実測表示であると主張しない。PEQ+CabinとMacro/Loudnessの**実ネイティブフィルタの伝達関数に一致した**カーブを別overlayで表示。Dynamic Bassは時間変動gainなので測定/telemetryがある場合のみ動的インジケーター、単なるstatic boost理論曲線と分離。
- 画面のsampleRateは`Renderer`が当該MEDIA streamで実際に使用する44.1/48等のrateから供給。媒体がないときは明示的なPreview sampleRateを選べるようにし、`NO_MEDIA / Assumed 44.1k`などと表示。実Rate復帰で再計算。
- 16～256点程度の曲線サンプルを計算し、係数生成は`remember(profileBands, sampleRate, overlay)`相当のmemoでUI thread負担を抑える。ドラッグ時の描画はフレーム上限／バッファ再利用に注意。TS10低速構成でComposeの長いGC停滞を防止。

### F2. 直接ドラッグと数値入力

```
 // 数式は設計定義（擬似コード）
 xNorm = clamp((pointerX - plotLeft) / plotWidth, 0, 1)
 freqHz = exp( ln(20) + xNorm * (ln(maxHz) - ln(20)) )
 yNorm = clamp((pointerY - plotTop) / plotHeight, 0, 1)
 gainDb = highDb - yNorm * (highDb - lowDb)
```

- `detectDragGestures`/`awaitEachGesture`などCompose pointer APIを使用。開始点から最も近い**有効な編集可能Bandハンドル**を選択（Hit Radiusをdensity/タッチ大きさに応じ調整）。Drag中のX=Freq/Y=Gainを対応フィルタタイプのみ操作、Qは選択Bandの独立Slider/±/数値入力。HPF/LPF/NotchはGain handleを禁止またはFilter固有の有意味操作へ変える。
- Drag hit testは`Cabin approved`のReadonlyとPEQのEditableを区別。複数Band同位置で選択を切替えるUI。Gain/Q/Freqの値が同じでpointer-upしたとき履歴を作らない。
- 数値入力：double全角/小数点/Hz/kHz/dB/Qをパースする場合はlocaleと単位を一意に定める。`NaN`, `Inf`, 負Freq, 非現実なQ、サンプルレート外、15超過は実DSPへ送らない。無効文字列はInputFieldでエラー表示し、既存値保持。
- 周波数がAudioRate/Filter条件で有効な範囲内なら、Commit時に`DspEqDesigner`による係数を再計算。音の反映はPhase04/Phase07のrouterへ渡す。Drag中はUIを滑らかに描き、Audio指令はcoalesce、pointer-upはfinal flush。
- `Preamp`と`AutoHeadroom`の表示、合成headroomの計算と実際のGain、Limiter telemetry（ある場合）を分離表示。グラフにマクロだけを重ねたものを『実測EQ』と呼ばない。

### F3. 正しさを数値で試す

- RBJ biquadの設計曲線がNative hostの周波数応答と一致することを、20Hz～20kHzのログ掃引で参照比較。設計上の許容誤差はPCM format/サンプルレート/フィルタタイプ別に先に定義し、事後に緩めない。
- CのMacro/Loudnessが1-pole系の場合、UI/Headroomで使う伝達関数をCと同じ式から厳密に導出するか、誤差を定量化して保守的上限を採用する。**KotlinでPEAK 2400Hz Q0.8の見た目を描いてCの500–4000Hzの1-poleとは完全一致と偽称しない。**
- 低サンプルレート境界 8k, 44.1k, 48k, 96k, 192k、Mono/Stereo、Band type→gain意味、Band重なり、FFT無し、極端Q、hover/click/touch、scroll親とのgesture競合。

### F4. テストDoD

`PH03-T01`: 5種以上Filter typeのplot Golden とNative Freq Response。`PH03-T02`: 20/200/2k/10kHzへdragするとfreq mapping誤差内。`PH03-T03`: gain−18/0/+18 mapping。`PH03-T04`: Q/type/数値 direct input。`PH03-T05`: 15 + Cabin capacity guard。`PH03-T06`: overlapping Band selected identity。`PH03-T07`: 44.1k/48k/NO_MEDIA Rate labelと曲線。`PH03-T08`: Undo1drag1stepとfinal Audio flush。`PH03-T09`: renderer vs graphics predicted curve error bound。

**Phase03完了条件:** Canvas自体が直接操作可能、全Bandを長く縦スクロールしなくても主要値を編集可能、数値入力と理論周波数特性・実Nativeの整合テストPASS。

---

## G. Phase 04 完遂：ライブプレビュー・保存分離・安全な取消し

**既存状態:** `previewStudioWorkspaceAsync()`/`saveStudioWorkspaceAsync()`/`restoreCommittedStudioWorkspaceAsync()`はある。Native Hot mailboxもあるがMacro/Loudness/DBスライダー入力は手動Previewボタンへ依存。`DspSettingsScreen`のCompose `SideEffect`がCoordinatorへ値を同期しUndo/タイミングの非決定性原因になり得る。CIのPreviewテストが実際に失敗している。

### G1. まず失敗テストを原因まで修正する

1. `./gradlew :common:connectedDebugAndroidTest`等を再実行し、失敗ログ＋Compose semantics treeを保存。単なる`waitUntil(15000)`への増加は原因解消ではない。
2. `DspSettingsScreenTest.kt:64`の`Unsaved DSP Studio draft`未検出について、Click後に**編集Coordinatorが本当にDirtyになったか**、Compose state→SideEffect→UI text順序、`Macro Power`の`StateDescription`、`snapshotFlow`/Recomposition、`Save`によるstate reset有無を計測。テストが早すぎるなら同期契約を修正し、耐久的なUI状態/semanticsで待つ。実バグならReducerへ再設計。
3. Preview callback→`DspProfileRuntime`内部snapshot/`studioApplyGeneration`、config store update、Native renderer active (存在する場合)を別々に検証。テスト環境でMEDIA rendererが無い場合は「ConfigProvider updated」と「Audio ACK」を混同しない。
4. 失敗を1回だけ潰したことを完了にしない。100操作、Split⇔FULL、background、cancel/save交錯、Activity再生成で再実行。

### G2. 明確なAPI・State machine（擬似型）

```kotlin
interface DspStudioEditSession {
    val state: StateFlow<StudioState>
    fun beginEdit(module: ModuleKey, field: FieldKey): EditToken
    fun updateDraft(token: EditToken, value: ValidatedValue)
    fun finishEdit(token: EditToken) // final Audio flush + one Undo
    fun applyModulePreset(module: ModuleKey, id: PresetId)
    fun selectCompareSide(side: CompareSide)
    fun requestPreview(source: PreviewReason)
    fun cancelToCommitted() // new request generation
    suspend fun saveWorkspace(): SaveResult
    suspend fun saveModulePreset(module: ModuleKey, name: String): SaveResult
}

data class ApplyIdentity(
    val editorEpoch: Long,
    val revision: Long,
    val requestGeneration: Long,
    val rendererOwner: Long,
    val pcmFormatEpoch: Long,
)
```

**実装詳細:**

- UI `onValueChange`→`updateDraft`→immediate StateFlow emission→control coordinatorへ`latest-only`request/50ms程度（端末測定で調整）→Native HOT更新/Stage WARM計画。pointer-up→last final Flush。長時間dragの時も音は中間値へ滑らかに変化。
- PreviewはPreferenceやWorkspace JSONへ書かずRuntime requestのみ。`Save`はatomic commitとProfile selected keyの整合性を保証し、**Save成功≠Audio適用済み**の2 statusをUIに別表示。保存完了時に現AudioがNO_MEDIAならNO_MEDIAのまま。
- Cancelは最後のpersisted設定を**新revision/generationで**Audioへ再要求。古いPreview/ACKのIDはstaleとして拒否。途中WARM/IR prepare中にCancelされたらprepared candidateを解放/retireし旧音は保持。
- `LifecycleOwner`ごとに比較/編集中Stateを再初期化せず、`DspProfileRuntime`または専用ScopeにCoordinatorを1つだけ持つ。Full/Compact/Activity recreationで同一Editor owner/同一Draftを再表示。ただしprocess deathでは**保存済みのみ**復元し、未保存Previewを勝手にcommitしない。Close画面で未保存のSave/Discardダイアログが必要。
- `DspProfileRuntime.applyWithImpulse`は旧Profile APIからのSharedPreferences保存と混在するため、**Previewから直接呼ばない**。既存V7 Legacy Profile選択による状態上書き競合も修正。
- Native ACKは`activeRendererGeneration`更新の前提条件。Audio blockの完了を確認できずPlaybackが無ければ`NO_MEDIA`。ACK後のSmooth rampが完了して初めて『完全に音が遷移した』扱いをする（デザイン上`RAMPING → ACTIVE`）。現行ACKが更新を含むblock終了しか示さないなら、表示ラベルを`APPLYING`へ分け正確にする。
- UIが消えても`volume`や`audio track`の購読は必要ならRenderer所有で継続し、データファイルなどユーザー識別情報をログへ記録しない。

### G3. スレッド安全と負荷の目標

- スライダー操作後目標約100ms以内にDSP Hotが音へ反映開始（サンプル0〜100msとは言い切らない。対象端末の実測とrenderer blockサイズで報告）。UIは60FPS近辺を目指すがGPU依存を測定。
- 100～500連続編集、A/B 20回、Cancel→Save→Undo→Native ACKの競合をdeterministic scheduler + Native stressで試験。
- Native JNI/stage process callbackではmalloc/free、lock wait、I/O、logging、GC誘発をしない。
- Audio request coalescing queueが溢れたら適切に最新値を再送して復旧。Hot enqueue失敗時に新値が古いACKより優先される。**最後のgesture値を取りこぼしたまま永遠にDEFERすることは禁止。**
- 復旧済みrendererの古いtimer/callbackがactiveへ戻した新generationを切断/undoしない。Audio失敗後はLast good Graph/latency preserving fallbackへ明示遷移。

### G4. 必須テスト

`PH04-T01`: Drag中のPCM変化とACK。`PH04-T02`: Gesture final flush=last sample parameters。`PH04-T03`: Cancel on in-flight Hot + Warm。`PH04-T04`: Save I/O失敗/Native後続失敗のstatus分離。`PH04-T05`: Activity recreate same Draft+renderer desired。`PH04-T06`: process death restores only persisted。`PH04-T07`: renderer absent NO_MEDIA、旧token無効。`PH04-T08`: close/rebind/format change/race。`PH04-T09`: UI test compose transitions 100 operations、同一SHAで実CI Green。`PH04-T10`: Previewだけで`SharedPreferences`/Workspace diskが変化しないこと。

**Phase04完了条件:** Native HOT対応の4 Macro/SmartLoudness/Dynamic Bassを楽曲再生しながら操作でき、保存せずCancel可能。latest request→renderer ack UI表示が現実と一致、FULL/Split/Activity再生成で保持され、確定CI instrumentationテストPASS。

---

## H. Phase 05 完遂：モジュール別A/B・Undo/Redo・状態表示

**既存状態:** `DspStudioSessionCoordinator.compareA/B`は**Workspace全体**。Undo stackはWorkspaceの過去版リスト、編集1イベントごと積む。UI A/Bはボタンを押すとWorkspace Previewするのみで比較用Gain/modeの分離やmodule scopeなし。

### H1. Scoped A/Bを製品の操作として定義

- A = 現在保存済み状態の**指定モジュール分**、B = 編集中状態の指定モジュール分。A/Bを作成する瞬間にImmutable comparison snapshotsを確定（切替中にドラッグされたらB snapshot更新/再比較を明示）。
- `COMPARE_SCOPE ∈ {moduleKey, macros, workspace}`。Module caseでは、**指定module以外の設定は共通の現在Base**。例えばBass A/B中にCompressorとCabinが切り替わってはならない。
- `compareA/compareB`が実音声へ反映される場合、Audio ACKを待ち、`Comparing A (ACTIVE)`/`B (ACTIVE)`。WARM/DEFERなら `Comparing B (DEFERRED; audio remains A)` のように正しく表示。A/B反復でold ack誤適用禁止。
- 基準のA snapshotは通常Save/Factory/比較開始時にfreeze。比較用snapshotと保存済みWorkspaceを混同せず、A/B切替で`Undo`やdirty historyを勝手に変えない。比較終了時は最新draft（B）へ明示的に戻す。中止/保存競合は最新Editor epoch優先。

### H2. 音量一致（Level Match）を正確に扱う

- `Level Match`スイッチを提供。初期段階で正確なperceptual loudnessを測定できないなら**「簡易音量合わせ（推定）」**と表示し、厳密LUFSマッチと偽装しない。
- A/B両方の同一音源Windowで短期RMS/LUFS相当を非Audio thread側で計算し、必要なら比較専用Gainを設定。ただし音源が異なる/連続音楽では信頼不可としてLevel Match unavailableを表示。比較用Gainは`DspAudioProfile.preampDb`、Macro、Workspace保存へ一切書き込まない。
- `TargetGain`の上限はHeadroom/Safetyと一体で管理。MATCH ON→OFF / A↔Bは20〜50ms程度のrampsでクリック防止。Limiter GRで比較が成立しない場合は警告。`Saved`とは独立。

### H3. Undo/Redoと状態表示

- Transaction-based UndoをPhase04のEditSessionへ導入。1ドラッグ1履歴、数値確定1、Module preset適用1、Factory Reset1、複数field Macro factory適用でも1。Undo最大100件、Redo扱い、Save後の履歴範囲は明文化。
- `dirty`は`draft != persisted`で判定。`requested`/`active`が一致しているかは別の状態で示す。Factory presetから1値変えたら`Custom unsaved`。ファイルSave成功前に`Saved`と表示しない。
- モジュールごとに表示する`Edit Status`＋`Audio Status`＋`Save Status`は別カラム/別テキスト。`Preview` buttonは保険の明示flushとして残してもよいが、**通常の手動操作を必須にしない**。
- `NO_MEDIA`, `ACTIVE`, `DEFERRED + why`, `FAILED + retry`, `SAVED`, `CUSTOM_UNSAVED`を単一画面/各Module cardから把握できる。

### H4. テスト

`PH05-T01`: BassのみA/Bで他module invariant。`PH05-T02`: CompressorのみA/B、WARM/DEFER表示一致。`PH05-T03`: A/B連打＋途中Undo。`PH05-T04`: 正しいLevel Match gainが**保存されない**。`PH05-T05`: 原音別windowでlevel-match unavailable。`PH05-T06`: 100ドラッグでもUndo1操作。`PH05-T07`: 同一値drag/異常値で履歴を増やさない。`PH05-T08`: Save失敗とACTIVE別表示。`PH05-T09`: NO_MEDIA表示。`PH05-T10`: Session close/cancel/recreateとA/Bのrace。

**Phase05完了条件:** 14種類のModuleをそれぞれ単独でA/Bし、音量一致UI/状態表示が利用可能。Undoがユーザーのジェスチャー単位で動く。実際の音声に一致しない比較成功表示なし。

---

## I. 新機能を完成させる：Macro / Smart Loudness / Dynamic Bass 2.0

Phase01–05のUIが完成しても、音が変わらなければ意味がない。**3機能の本番RendererまでEnd-to-Endで検証すること。**

### I1. 音質マクロ — 最重要機能

- 4 Macro：`Bass Impact`, `Vocal Presence`, `Air / Detail`, `Soundstage Width`。各0～100、50 Neutral、Master OFFなら全影響0。Macro OFF時に4値を**保持**し再ONで復元。旧PEQ/Bass/M/S/Harmonic等のBase値は一切変更しない。
- 現行`DspSoundMacroSettings`→`DspTuningResolver`→`DspMacroToneConfig`→`prepareHotUpdate`→Native `dsp_macro.c`の一本のデータ経路を維持。UI値の変更で実AudioをHOT更新する。70ms程度のinput coalescing候補＋pointer-up即時flush。**Native ACKと実PCM変化**でDoDを判定。
- マクロFactoryの安定ID/初期値を完成させる。`macro_neutral_v1`=50/50/50/50、`macro_vocal_v1`=50/75/60/55、`macro_punch_v1`=80/55/55/55、`macro_clearwide_v1`=50/65/70/80、`macro_warm_v1`=65/50/40/45、`macro_night_v1`=40/60/40/45。初期値は調整可能な製品候補であり高音質保証ではない。Factory不変、User Macro Preset保存/削除/比較に接続。旧 `DspFactoryPresets.macroPresets` の4件と新Factory6件の重複は1つの正本へ統合。
- Native Macro `dsp_macro.c`の現在の特性はfirst-orderの低域180Hz、Vocal帯500–4kHz、Air 8kHz付近を利用。Kotlin Headroom/EQ Graph側でモデル化したRBJ shelving/PEAKと**一致しているか定量化**し、周波数ごとの実効Boostが予測を越えないよう保守的に評価。安易に全Stageのフィルターを置換し旧音質を変えない。
- Macro Widthは既存Advanced M/Sとlegacy Widthの**相対ゲイン**として演算。MONO入力でステレオ化しない。Stereo左右の位相逆転やセンター消失を回避し、Neutralでbitwise/defined numerical golden一致。
- 操作結果をユーザーへ説明：例えば `Bass Impact 70 → Macro low boost +? dB`、`Headroom limited`。Boostが6dB budgetで制限された場合、UIマクロ値と実行補正は別表示。スライダーの表示値をこっそり変更しない。
- NativeHot Gain ramp20ms程度を初期目標とし、音切れ/クリック/デノーマル/ステレオ連続性をテスト。過度な高域Boost/旧Compressorへの影響を聴感・Sweepで評価。

**特に重要なテスト:** Macro Neutral全バンド旧DSP golden、0/50/100×各Macroの実PCM＋理論カーブ、100連続ドラッグとUndo、連続A/B、Mono入力、0ボリューム/Fullscale、Native未ロードやhandle close、保存後再起動。

### I2. Smart Loudness — AUTOの実装完遂

- `OFF/AUTO_CALIBRATED/MANUAL_ESTIMATE/FIXED`の4モードを維持。補正の源は**再生音量**であり音楽のRMSではない。Dynamic Bass検出器とは別系統。
- 現状`DspProfileRuntime.volumeSource = AtomicReference<DspVolumeSource>(ManualVolumeSource())`のためAUTO実際の入力がない。Android `AudioManager`, `getStreamVolume`, `isVolumeFixed`, 実オーディオ経路/出力デバイスを調査し、**Android音量ソースを正式に配線する**。調査した `STREAM_MUSIC` volume indexだけでTS10の物理ノブを信頼しない。走行中ではなく停車時校正を必須にする。
- 重要条件：TS10/MCUのハードウェアボリュームがAndroid indexと非連動なら`AUTO_UNAVAILABLE`が**正しい実装結果**。AUTOが使えると嘘をつく実装は不合格。Android sourceから妥当なindex＋route identityを取得できる場合でも**OEM knobとの対応が実機で未証明ならtrusted=false**とし、MANUAL/FIXEDを使えるようにする。音量ソースは信頼可能性とサポート端末の制約を明記。
- SourceのLifetimeはRenderer/MediaSession ownerの`DspLoudnessVolumeMonitor`。**UI可視時だけの`LaunchedEffect`に依存しない**。音量イベント/システム通知＋必要に応じて合理的な頻度のpollをControlThreadで実装。UI非表示でもAudioが動き続ける。Audio処理コールバックで`AudioManager`アクセス禁止。Audio/Siri/Call/Route changeはsource identityを更新、途中復旧のold calibrationを使わない。
- `RouteToken`には機器固有ID等のPIIを保存/公開せず、アプリ内プライベートstable mappingを用いる。校正のquiet/reference2点は同じroute、単調変化/不変ボリューム時は拒否。NaN/Inf/0最大/例外/固定音量をfail closed。
- 補正：最大低域+3dB/高域+1.5dBは初期候補。低音量ほど単調にBoost増、基準音量では0に近づく。音量だけが変わればHOT更新で短いsmooth。楽曲RMS変動でSmart Loudness gainは変化しない。画面には「今のroute」「信頼できる/手動」「現在の補正」「限定条件」「基準点」を表示。
- ユーザーが手動モードにした場合は**実Android音量は変更しない**。`FIXED`は音量監視が失われても一定。`AUTO`が失敗したら指定された安全fallback（0 compensation/設定値保持）を採り、勝手にMANUALへ値を書き換えない。

**テスト:** 画面Closed時音量変更、Android stream index同一でOEMノブのみ変化（AUTO_UNAVAILABLE）、route changed、校正不一致、固定音量端末、Bluetooth/USB異経路、rate44.1/48、旧音質、A/B/Cancel/Save、gain smoothing。

### I3. Dynamic Bass 2.0 — detector tap + processing orderを修正

- `dsp_dynamic_bass.c`は独立LPF、RMS系ステレオリンク、Gate、Threshold、Attack/Release、Gain cap実装済み。**改修時これを破壊して全新実装しない**。未達は検出Tap/ステージ配置と安全なゲイン変動の証明。
- 現在の`dsp_graph.c`では Compressor→Multiband→Dynamic EQ→Harmonic→Convolver後にDynamic Bassがあり、`dsp_dynamic_bass_process_frame()`が後処理済みL/Rから低域レベルを検出する。仕様要件は**元PCMもしくはpost-input-gain/preboostの明示的な一定Tap**。まずどこを検出基準にするか固定し、その信号のStage Input所有者とブロック間状態の継続を明確化する。
- 推奨Graph：Raw MEDIA inputからimmutable detector L/Rを取得→既存Preamp/Seat/PEQ→Manual Bass→Macro Tone→Smart Loudness→Dynamic Bass(検出はraw/preboost tap)→Compressor→Multiband→DynamicEQ→M/S/Width→Harmonic→Convolver→Safety limiter。**既存Graphの処理順が変更されるため、単なる移動でOKとみなさず**legacy all-new-OFFのGolden、Compressor pumping、phase、delay、saturationを再テスト。Convolverのupstream/downstream位置はmix latencyやfeedbackの観点で再評価。
- API例（擬似コード）：`dsp_dynamic_bass_process_frame(bass, detector_input_LR, &signal_LR)`。raw detectorはPCM sample処理直前でfeedし、自己Boost後信号をdetectorに与えない。左右DetectorはStereo-linkedで左右Gain値同一。Monoは同一channelの値とする。
- Gain capは `0..effectiveMaxBoostDb` を守り、無音時0、low energy時上限、過大低域時0へ戻る。full scale pulseに対しAttackが遅すぎてovershootしないようテスト。`pow`, `sqrt`, `exp`等のオーディオスレッド内 per-sample使用回数を計測し、性能上必要なら事前係数と制御サブサンプリングで最適化（信号処理結果はGoldenで一致）。
- `Dynamic Bass 2.0`と旧`Dynamic EQ`、`Virtual Bass`、`Bass Shelf`の意味を区別。Volume-driven Smart Loudnessとは別のDetector。ユーザーが強い低域にするとHeadroom managerがまず追加Boostに制限をかける。
- Process disabled/strength0/maximum0ではベース音質不変。急なOFF/ON/旧セッションdestroyで状態を適切にリセット。制御バッファ過負荷時の最大Gainを超えない。Native float finite guard必須。

**テスト:** 固定raw detectorで既存Compressor/IR設定だけ変えてもDetectorが不意に変わらない（処理順によりAudio出力は変わってよい）、Mono/stereoリンク、無音、Kick/808/tonal sine、blocked processing、分割境界、44.1/48k、CPU/p99、ASan/UBSan。

### I4. 共通Headroom/Boost Budgetとステータス

- 追加低域のMacro+/Loudness+/DynamicBass Maxの合計は**+6dB以下を初期Budget**として保持。手動Bass Shelf・EQ・Compressor Makeup・DynamicEQの正Gainとは別途**全体ヘッドルーム**評価。+6dB以内だからクリップしないとは断言しない。
- NativeステージがRBJ biquad予測と違う場合、計算されたヘッドルームは危険な過小評価となりうる。**C実行Stageの実伝達関数**を計算モデルへ反映するか、誤差分を保守的に最大評価し、Sine SweepとFull Scale step/pulseで検証。TruePeakを保証しない旧Limiterの性質は維持し、そのまま明示する。
- 今の`DspTuningResolver`は追加Bass Budget内で DynamicBass→Macro→Loudnessの順に資源割当。これは採否理由を含めUIへ表示し、カーブ/エフェクト組合せで実行BoostがRequestedより小さいとき `Limited by headroom`と説明する。
- `Headroom`再計算のためNative full rebuildを毎音量キーイベントで行わない。一定worst-case budgetでpreamp headroomを予約し、短いGain rampをHotで適用。Boost/Dynamic/Preampの同一世代コマンドをAtomicに採用。
- `Limiter`はSample Peak制限。intersample peakはoversampled offline measurementへ分離。Limiter GR連続高値、CompressorのBass pumping、Harmonic拡大したUpper Harmonicsを検査。

---

## J. Phase 07 完遂：旧DSP全モジュールの安全なリアルタイム変更

**現状の厳密な区別**：`DspUpdatePlanner`は実装済みだが、`DspRuntimeConfig.canApplyStudioHotUpdateTo`が許可するのはMacro/Loudness/DynamicBassのみ。残りは既存WholeGraph準備/グローバルstateful判断でWARMまたはDEFERRED。これでは`Phase07 Live`の目標未達。

### J1. 更新分類をパラメータ粒度へ拡張

実装対象と推奨初期区分（**安全性実証前の候補であり、未検証の途中切替はDEFER可**）：

| モジュール | 差分フィールド | 推奨方式 | 音声側不変条件 |
|---|---|---|---|
| Preamp | gain dB | HOT | 同世代Safety gainをramp、state不変 |
| Macro / Loudness / DynamicBass | gain/threshold/attack等 | HOT | 新3Stage Hot/ACKの既存設計を拡張 |
| EQ | enabled band gain/freq/Q/type | WARM_LOCAL | 15共用容量、filter history warmup |
| Bass Shelf | gain/freq/enabled | HOT gain候補/WARM_LOCAL | z1/z2を不正コピーせず、phase/clip制御 |
| Seat | left/right trim | HOT候補 | L/R gain rampとセンター保持 |
| Seat | distance delay/near speaker | WARM_LOCAL/DEFER | 相対Latency/PlaybackClockを壊さない |
| Seat | invert L/R polarity | WARM_LOCAL/DEFER | 0-crossing/fade、明示危険Confirm |
| Legacy Width | width | HOT候補 | Mono=unchanged、M/Sと重複制御を表示 |
| Advanced M/S | center/side boost, width | HOT候補 | envelope継続、image/phase制約 |
| Advanced M/S | crossover freq/side comp topology | WARM_LOCAL/DEFER | LR complementary phase、歴史暖機 |
| Compressor | threshold/ratio/attack/release/makeup/knee | HOT候補 | envelope継続、Gain/limiter同世代 |
| Compressor | stage enable/mode | WARM_LOCAL | sidechain history/hidden state継続 |
| Multiband | threshold等各band gain/ratio | HOT候補 | 3 band envelope継続 |
| Multiband | crossovers/on/off/topology | WARM_LOCAL/DEFER | LR4再構成/位相/メモリ |
| Dynamic EQ | band threshold/ratio/maximum boost | HOT候補 | detector＋current gainの歴史継続 |
| Dynamic EQ | band freq/Q/type/on/off | WARM_LOCAL/DEFER | Detector LPF/biquad warmup |
| Harmonic | Amountのみ | HOT候補 | FIR phase/oversampling不可変 |
| Harmonic | mode/quality/oversampling | WARM/DEFER | FIR group delay可変、A/V同期 |
| Convolver | Wet（同一IR） | HOT候補 | active FFT overlap等の状態継続 |
| Convolver | IR ID/ON/OFF | WARM/DEFER | 128frame等common latency、IR history |
| Cabin Correction | Gain/Type/Freq/Notch | WARM_LOCAL | Approved source/15共用制限 |
| Safety Limiter | threshold/release | HOT候補 | リミッタenvelope継続、突発無制限Gainを起こさない |
| Master | ON/OFF | 専用経路 | fresh TrackのPCM16 bit-exact、midtrack latency保存 |

**最低実装ゲート:** Preamp、Macro、Loudness、DynamicBass、Limiter・Compressorの妥当性のあるパラメータ、Seat TrimなどについてHOTの実装可否を個別に結論づけ、**安全に可能なものはNativeまでHOT接続**。PEQと既存Bassの局所Warmは今回の重点。無理なstateful IR/mode切替は理由付きDEFER。どのパラメータでも`Preview`手動を常用せず、変更音はすぐに反映されるか、**なぜ反映できないか**表示。

### J2. `DspUpdatePlanner`をdiff classifierとして作り直す

```kotlin
// 例：type-safe diff。実コードは既存DspRuntimeFieldと共存/置換可
sealed interface UpdateDecision {
  data class Hot(val fieldMask: Long, val generation: Long): UpdateDecision
  data class LocalWarm(val stage: StageKey, val reason: String): UpdateDecision
  data class GraphWarm(val reason: String): UpdateDecision
  data class Deferred(val reasonCode: String, val earliest: NextSafeBoundary): UpdateDecision
  data class Failed(val reasonCode: String): UpdateDecision
}
```

1. 比較は**current active graph vs requested**の不変差分から行う。`enabled`=falseなどの同等操作はNoOp扱い。`requested config = active config`でも旧Deferredがあれば世代だけでACTIVEにしない。
2. activeにConvolverがあるだけで無関係なMacro/Preamp/EQへの変更をDEFERする旧`requiresStateContinuity()`を修正。判定には**実際に変更するStageがstatefulか、旧Stageの状態を維持したまま新Stageがwarm可能か、committed track latencyに影響するか**を使用。
3. HOTはNative ABIが実際に対応した型のみ許可。`classify(HOT)`しておきながらJNI payloadにフィールドが無いのは重大欠陥。追加前にKotlin prepared config→C stage update mask→Audio thread Ack→testsを一式実装。
4. LOCAL_WARM対象Stageの準備・履歴・crossfade/cleanupが未実装なら`DEFERRED`へ安全にフォールバックし、UI理由コードで表示。WholeGraph fadeとLocalWarmを同じモード名称へ紛らわせない。
5. 保持が必要な状態：Compressor envelope, DynamicEQ detector/envelope、Multiband crossover histories、M/S side comp、Convolver FFT overlap、Harmonic FIR, Seat fractional delay等。無関係Stageはresetしない。
6. Diff中にsample rate/PCM encoding/channel/Renderer owner変化が発生したら旧candidate失効。Callback返却後の古いACK無視。AudioTrackをユーザー編集のたびに再生成するな。

### J3. Native HotのABI/Lock-free mailbox/世代契約

- 現行`dsp_engine.c`にsingle-slot atomic queue+ACK token、`NativeDspProcessor`に`applyHotConfig`がある。**既存実装を保守しつつ拡張**する。複数producerの可能性があるなら、UI/volume/Compare/Cancelの全要求を単一Control writerへシリアライズするか正しいMPSC queueに作り替える。SPSC前提をコメント/テストで明文化。
- Native commandにはschema/version、field mask、`generation`、`editorOwnerEpoch`、`rendererOwnerToken`、`pcmFormatEpoch`、`hotContextToken`、mediaRole、Target gain、filter coeffまたはimmutable prepared pointerを含める。C11 atomic acquire/release、write/publish/read/ack/slot reclaim順序を検証。**単純`volatile`は禁止**。
- `dsp_graph_apply_studio_hot`がgainを先に適用しMacro validationが後という形のpartial mutationに見える場合は、**全値を先に検証してから一括適用**し、途中失敗後に前値を維持。Native validationはKotlin側に依存しない。
- 途中Renderer close、`bindHotUpdateContext`を再実行、AudioThreadがQueueを読み終えた直後に新ownerとなる競合に対し、旧ownerの更新が適用されないかテスト。TagだけでなくNative handle lifetime lease/Reaperと整合させる。
- UI/Coreの99th percentile遅延のため、Audio threadから`pow/exp/log`や`new Filter/alloc`を呼ばない方向で係数はControl thread準備。現行`dsp_dynamic_bass.c` per-sample `sqrt`等はベンチ次第最適化。音響誤差は定量テストで確認。
- Audio PCM callback内は`malloc/free`, mutex wait, `synchronized`, disk IO, per-sample JNI, UI action, ordinary log出力を禁止。Nativeエラー時に旧parametersを維持しDiagnosticsをlock-freeで収集。

### J4. LOCAL_WARM stage isolation

- PEQ/Static Bass：新しいcoeffセットをControl側で準備。旧Stageを独立継続処理、新Stageを**同じStageへの入力信号履歴**で暖機。新/旧の出力を短い等ラウドネス/同レイテンシcrossfade。`z1/z2`を変更前後のcoeffで無条件コピーしない。
- 参照履歴は`raw PCM`ではなく**stage直前の処理済み信号**を必要数リングバッファに保持。ringはAudio作業中にallocateしない。処理できない場合はStage毎の内部input tapを記録・準備workerへ安全に渡す。warmupのための履歴確保が過大なら安全にDEFER。
- Compressor/Multiband/DynamicEQ/AdvancedMSなどは差分に応じてenvelope/coeff/historyのどれを維持するか設計し、段内A/Bレンダリングでold/newの位相/音圧差を測定。Convolver/Harmonicは特に高リスクなのでデフォルトOFF/DEFER可。ただし**未実装ステージをLOCAL_WARM対応と呼ばない**。
- 多モジュール同時編集は最も安全性が必要なStageへ分類。LocalWarm中に新Hot値が来た場合、shadowを作り直すか新コマンドをqueueingし、旧candidateを必ずretire。`liveFadeInProgress`中のpending update dropを防ぐ。

### J5. レイテンシ維持・Convolverの契約

- Legacy Convolver最大64k IR、128-frame partition latency、Harmonic FIR 9/17/33タップの4/8/16 group delay等の既存値は必ず現行コードで再確認。共通delayをmid-trackで勝手に変更禁止。
- Track開始時に`LiveLatencyBudgetFrames`を予約する設計は**実機とA/Vの証明ができた場合の実験機能**とし、新3機能HOTを実現するために必須にはしない。OFF+新Trackのbit-exact旧出力に影響しない。
- Convolver Wetだけを更新する時でもIR active, loaded, rate一致、state保持、fadeでクリックしない条件が必要。IR changeではpartition長と共通Latencyが変わるなら安全にDEFER。Surface再生成やCarPlay再接続を『解決策』として使わない。
- Meta/Pcm format変化、A/Vのtimebase整合、fallback delay、Retirement queue失敗、memory OOMの各テストを新規追加。

### J6. Native/Renderer検証必須ケース

`PH07-T01` HOT Preamp+stereo 100連続、frame間Gain smooth。`PH07-T02` CompressorがONのままPEQだけ変更→デファーせずLOCAL_WARM、旧Compressor envelope不変。`PH07-T03` ConvolverがONでMacro Hot→Graph全再構築なし。`PH07-T04` DynamicEQ threshold HOT、Freq WARM分類。`PH07-T05` Multiband crossover phase、旧state維持。`PH07-T06` ConvolverIR変更、latency不一致ならDEFER。`PH07-T07` Seat distance変更、Track latency/relative delayを守る。`PH07-T08` Native hot command/close/rebind/formatchange/stale ownerでno UAF。`PH07-T09` Hot latest wins while Warm preparing。`PH07-T10` Media only、Siri/Call role isolation。`PH07-T11` Linux/host ASan+UBSan、Android ABI 3+一致。`PH07-T12` DSP OFF freshTrack legacy PCM16 golden。`PH07-T13` Shadow stage warm signal input history一致。`PH07-T14` limiter and safety budget atomic update。

**Phase07完了条件:** param-per-field matrixの各行に`HOT_CONFIRMED/WARM_LOCAL_CONFIRMED/SAFE_DEFERRED/UNSUPPORTED`を証拠付で記録。コアHOTとPEQ/Bass局所warmは動作・Native test・Renderer integrationまで検証。High-risk IRで安全条件に達しなければ**安全なDEFERを実装し、残存の制約を明記**。その条件では`IR_LIVE_READY=false`であり「すべて即時反映」と宣言しない。

---

## K. 必須の実行工程・コミット分割（レビューしやすい作業台帳）

**注意:** PR #52に既に存在する60ファイルを再度まとめて大量整形しない。必要な差分だけを実装し、各commitで機能/テストを対応付ける。依存を跨がず、レビューの境界を明確にする。

| 実装Packet | 内容 | 主要担当/ファイル | コミット受入ゲート |
|---|---|---|---|
| `FIX-00` | ベースライン再取得、CI failure原因、field coverage、テスト雛形 | docs, tests | HEAD/環境/失敗ログ固定。テスト削除無し |
| `FIX-01` | 単一Coordinator、保存Profile不整合、Editor transaction | Runtime, StudioPersistence, EditorSession | CR-005/006/019・Phase01保存試験 |
| `FIX-02` | Preset Delete/Reset、IR ReferenceGraph、14 keys & Macro factories | StudioPersistence, UI presets | PH01-T01..10 + 保存/参照golden |
| `FIX-03` | 5タブShell、全旧Control移行、Split UX | DspSettingsScreen + 5 tabs | PH02-T01..07、Legacy Field Coverage |
| `FIX-04` | EQ Canvas gestures、Q/Numeric、rate、overlay、性能 | EqTab, DspEditorPanes, EqDesigner | PH03-T01..09、Native curve比較 |
| `FIX-05` | Preview Coordinator、drag coalescing、Save separation、NO_MEDIA/ACK | Runtime, Renderer bridge, UI | PH04-T01..10、録音不要のPCM E2E |
| `FIX-06` | Scoped A/B、Level Match、transaction Undo、Module status | Session, ComparisonBar, ModuleCard | PH05-T01..10、保存不変 |
| `FIX-07` | Native Gain/HOT routing拡張、Stage API、Filter diff | Kotlin/JNI/C | PH07-T01..04,08,10,11,12 |
| `FIX-08` | PEQ/Bass LOCAL_WARM、stateful安全延期、format/latency | Graph, UpdatePlanner, AudioSink | PH07-T02/05/06/07/09/13/14 |
| `FIX-09` | Smart Loudness AUTO source/Monitor、Macro factories/UX | Runtime, VolumeSource, Studio | CR-002/003/004/014、T-LOUD全件 |
| `FIX-10` | Dynamic Bass preboost Tap、Gain Budget/Headroom整合 | dsp_graph, dsp_dynamic_bass, resolver | CR-007/021、T-DB/T-SAFE全件 |
| `FIX-11` | feature flag、CI安定化、WholeGraph/Renderer regression、UAT | CI, docs, tests, runbook | 全testID、四分野レビュー、NO_GO gate |

工程は依存関係に応じて調整可。ただし**Preview Coordinator/ownerの設計（FIX-01/05）に基づかないローカルUI独立実装を先にマージしないこと**。Native ABIの変更はまず小さなAPI契約文書で固定し、Luna/UI subagentへ渡す。共有ファイルへの並行変更・rebaseにより互いの改修を上書きしない。

### K1. コミットメッセージ例

```text
fix(dsp-studio): single-owner editor state and persistent workspace reconciliation
feat(dsp-presets): scoped delete reset and IR reference protection
refactor(dsp-ui): five-tab studio and compact split module cards
feat(dsp-eq): interactive parametric graph and numeric edit
feat(dsp-live): continuous preview, final flush and renderer acknowledgement
feat(dsp-compare): scoped A/B and transactional undo
feat(dsp-native): hot gain and stage-local warm update support
fix(dsp-dynamic-bass): preboost detector tap with audio regression tests
fix(dsp-loudness): playback-owned trusted volume monitoring
fix(dsp-ci): instrumented reproducibility and benchmark diagnostics
```

全コミットに`Fixes CR-*`, `Covers PHxx-T* / T-*`, 実行コマンド、出力場所、リスクを書き、PR本文に変更履歴を追記する。`docs/dsp-studio/CHANGE_LEDGER.md`をレビュー対象とする。

---

## L. テスト基盤・CI修復計画（今回のマージの主要ゲート）

### L1. 直近2つの実際の失敗を分離

**ケースA：`a0b197a9`のActions #40249136921**

- `common:connectedDebugAndroidTest`で`DspSettingsScreenTest.kt:64`の`assertExists`失敗。これは`Compose`のSemantics/Dirtyへの反映の競合か実装不備をまず調べる。
- `shared:connectedDebugAndroidTest`でConvolver64k 48kHz mono `p99=30.477ms` > `21.332ms`。単発/エミュレーター変動か実回帰か不明。

**ケースB：`ba6b6592`のActions #40250193181**

- 最新確認ではActionsは**completed / failure**。`check`, `host-baseline`はsuccess、`quick-baseline`はskipped、`android-instrumentation`と`Release verification`はfailure。
- このrunの`common` UI testは少なくとも前述の `Unsaved` assert失敗としてログに出ていない（6/6が完走）。ただしテスト名/実行数・レポートXMLを確認するまで全面PASSを推測しない。
- `shared:connectedDebugAndroidTest`の**性能テストが2件FAIL**：`DspPipelineBenchmarkInstrumentedTest.benchmarks48000Stereo` の `FullHeavyChain` p99=`34925us`、`benchmarks48000Mono` の `PEQ_Compressor_Limiter` p99=`28297us`。閾値`21332us`。**同じrunで入力シナリオの異なる測定が失敗している**ので雑に「誤差」と片付けない。
- 修正は「閾値変更」ではなく、同一AVD/ホストCPU負荷のbaseline PR45と新HEADの同条件比較、warmup、GC/JIT、CPU affinityの確認、繰返し3–10run、p50/p95/p99分布・サンプル数、Native温度/clock/debug flags、branch diffを収集して原因判断。
- AVDでベンチが非決定的なら性能試験を**機能ゲートと性能観測ゲートへ分離**できる。ただしfalse-GREENにならないよう、確定性能基準を物理ターゲットで必ず実施し、AVDのFAILは`FLAKY_INVESTIGATION`として追跡。CIで無条件skipせず、`host/native timing`と`target Android timing`を別出力。

### L2. 必須コマンド（実際のタスク/フレーバーを確認してから実行）

```bash
git status --short
git branch --show-current
git rev-parse HEAD
git remote -v
git log -5 --oneline
rg -n '(canApplyStudioHotUpdateTo|requiresStateContinuity|DspStudioSessionCoordinator|DspModulePresetRepository)' common shared

# Native host、sanitizer:
bash scripts/test_dsp_native.sh
bash scripts/test_dsp_native.sh --sanitize

# Core Kotlin JVM suites:
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest --parallel

# 必要なAndroid compilation/packaging:
./gradlew :mobile:assembleDebug :automotive:assembleDebug
./gradlew tasks --all
./gradlew :mobile:assembleBenchmark :automotive:assembleBenchmark
python3 scripts/check_jni_mapping.py

# Emulator / connected targetがあるとき:
./gradlew :shared:connectedDebugAndroidTest :common:connectedDebugAndroidTest

# 追加: lint、配布の各variant、公開tree/EX-08/CI classifierなど
# リポジトリ実在のCI workflowからコマンドを自動確認すること
```

- コマンドが1つ失敗したら、その出力/終了コード/ログ/HEADを記録して修正し再実行。`--continue`で先のテストを進めてもFAILを成功に読み替えない。
- `check_jni_mapping.py`は必要なmapping生成物があるvariantを特定し実行。ない場合`BLOCKED_ENV`、勝手にfakeファイル作成しない。
- 動的サニタイザーがホストで利用できなければ、対応CI runnerや別環境を検討し、対応不可なら`BLOCKED_ENV`。`ASan/UBSan`実行していないのにPASSと報告しない。
- 変更がJNI/Cに及ぶ場合、3+ ABI（arm64-v8a, armeabi-v7a, x86_64）compile/linkとNative Host C11-Werrorを通す。
- test XML（`tests/errors/failures/skipped`）とActions job conclusionsは別々に記録。`quick-baseline skipped`はPASSではない。RUN/CHECKの先行/依存workflowも確認。

### L3. 差分に必要な新規テストクラス（推奨）

```text
common/src/test/.../DspStudioCoordinatorTest.kt
common/src/test/.../DspModuleScopedComparisonTest.kt
common/src/test/.../DspStudioWorkspaceMigrationTest.kt
common/src/test/.../DspVolumeMonitorTest.kt
common/src/test/.../DspFactoryMacroPresetTest.kt
common/src/androidTest/.../DspStudioFiveTabsTest.kt
common/src/androidTest/.../DspEqGraphGestureTest.kt
common/src/androidTest/.../DspModulePresetLifecycleTest.kt
common/src/androidTest/.../DspStudioPreviewRecreationTest.kt
shared/src/test/.../DspHotWarmFieldMatrixTest.kt
shared/src/test/.../DspRendererPreviewAckTest.kt
shared/src/test/.../DspHeadroomActualResponseTest.kt
shared/src/test/native/dsp/test_dsp_engine.c             // 拡張
shared/src/test/native/dsp/test_dsp_live_stages.c       // 作成する場合
```

- TestNameに`PH02-T03`, `T-LIVE-14`などを含めるか、`docs/dsp-studio/TEST_MATRIX_DETAILED.md`でGitHub permalinkとTest Methodへ1対1マップする。
- 実Android Compose Testingでassertが意味論的に正しいNodeを選ぶこと。`onNodeWithText("Enabled")`の曖昧さ、scroll状態、Unbounded Layout、main-looper idle、IO callback、Native ACK待ち、emulator jitterを扱う。
- 「assertするダミーStateだけをテストに表示する」修正を禁止。Repoに保存する試験は**本番UIと本番Rendererの統合経路**を可能な限り通す。
- 本番CarPlayがないAndroid Emulatorではsession/Surface/Touchの物理同一性は証明できない。Fakes/MOCKは`SIMULATED_PASS`と区別。

### L4. 必須障害注入と安全性試験

| 故障・競合 | 期待する挙動 |
|---|---|
| Native handle close直後にUI Preview ACKが返る | 古いtokenはDiscard、UI新Session不変 |
| 同時にHot、Warm、A/B、Cancel | 最後のintentだけがActive、旧Graph/ReaperにUAFなし |
| IO中に保存先read-only/容量不足/部分JSON | 旧保存ファイル保持、WRITE_FAILED、Audio PreviewとSave状態分離 |
| IR import中にmodule preset削除/Editor閉鎖 | IR参照の安全性、古い結果で他Preset上書き無し |
| PCM Format 48→44.1k、Stereo→Mono | 旧コマンドreject、正しい新coeff/warm history、latency維持 |
| Siri/Callへrole switch | `MEDIA-only DSP`が非MEDIA PCMを触らない |
| 15 band有効＋Cabin2を適用 | 容量超過reject、勝手に下のband削除無し |
| Future schema→Save/Reset/Delete | 元の未来schema保持、書込み拒否 |
| Session Activity再生成/Background→Foreground | Pending編集の定義した復帰、Audio/Persistenceの嘘無し |
| Native Stage失敗→bypass | Last-good またはlatency preserving fallback、CarPlay接続維持 |
| 1000操作/20タブ切替/20 Split | UI ANR/Crash/Audio drop/Memory leak無し、計測結果記録 |

---

## M. 受入定量指標・実機検証とロールバック

### M1. 指標（目標と合否を分ける）

- 新3機能の**追加アルゴリズム遅延=0 frame**を48k/44.1、Mono/StereoでNative Test。旧Convolver/Harmonic既存遅延を加算して誤表示しない。
- Audio Threadのsteady-state allocation=0、lock/I/O=0。512-frame/48kHzの約10.67ms block periodに対しp99が70%以下を候補性能目標、増分p99 +1.0ms以内目標。これは**ターゲット端末実測で最終評価**し、エミュレーター固有性能を絶対保証にしない。
- UIのスライダー操作→Audio反映開始まで約100ms以内を初期目標。`UI feedback immediate`と`renderer ACK`、`smoothing complete`を分けて計測。Slow deviceで未達なら測定値と改善・制限を報告。
- DSP OFF新TrackのPCM16 byte-for-byte、MEDIAだけDSP、44.1/48kMono/Stereo、15PEQ+Cabin、DynamicEQ≤5、Clip/TruePeak（測定とLimiter保護の区別）を守る。
- アルゴリズム切替のクリック・ポップ：silence/impulse/step/sine/sweep/musicで、変更前ベースラインと比較。テスト開始前にmetric/thresholdを定義し、音圧を無理に上げない。
- A/V Clock/Surface ownership：コール/案内/Siri/動画/CarPlay55:45 Full↔Splitを20回以上。再接続無し、タッチ座標維持、音声再生継続を**session identityが一致**することとともに示す。
- UIの安全な操作：音声の高音圧や運転中の複雑な調整を避ける注意文言、スクリーンreaderとタッチtarget、キーボードオーバーフローを確認。

### M2. TS10/K706 + iPhone Runbookに必ず含めるもの

1. 機種/Android API/CPU ABI/RAM、DiPlay APK version/commit SHA、CarPlay接続種類、AudioSampleRate、PCM format、Session/Renderer owner匿名化token、テスト音源情報を記録。
2. 新3機能OFF・旧Profileでgoldenを採取し、Stereo L/R極性・定位/遅延・Input/Output RMS/Peak、Listener位相、CarPlay sessionを記録。
3. Macro B/V/A/Wを音量を上げずに各0/50/100で連続ドラッグ、音の変化とACK、Headroom、Limiter GR、CPU p99。保存せずCancel、Save→再起動、Preset A/B。
4. Smart Loudness物理ノブ vs Android AudioManagerの対応表。OEMノブ変化のみでindex不変なら`AUTO_UNAVAILABLE`、MANUAL/FIXED試験は継続。
5. DynamicBass Kick/808/同一Bassの弱強、コンプレッサーとの共存、20回Enable/Disable、音圧安全、Clip/THD、thermal throttling。
6. 15Band EQ drag数値入力、Freq/Type/Q、Cabin2とのcapacity、Mono/Stereo/44.1/48kの曲線表示。
7. CarPlay Split55:45を連続20往復、FULL/Compact、Surface/MediaCodec/AudioTrack/session維持とタッチ座標。画面回転/Activity再生成を加える。
8. Siri、Phone、Navigation、AudioMessage、YouTube Split、USB drop→recoverでDSPのMEDIA-only/Session owner/Volume routeを確認。
9. 30～60分連続音楽/操作、native RSS/Java heap/GC、CPU、p50/p95/p99、Underrun、A/V drift、日常ヘッドユニットの安定性を記録。
10. 異音・フリーズ・音割れ時の緊急無効化スイッチ/旧UI経路/既存Profileのみへのrollbackを試す。

**端末が無い場合:** `docs/dsp-studio/HARDWARE_RUNBOOK.md`を詳細に実装して完了できるsoftware部分は最後まで完遂。ただし`HARDWARE_PASS=false`、`MERGE_READY=false`、`MERGE_NO_GO_HARDWARE_PENDING`を記録。人間に対して実機操作の協力手順・取得ログ形式を残し、測定結果を捏造しない。

### M3. Rollback/段階公開

- `DspStudioFeatureFlags`の本番読み取り/安全なデフォルトを実装し、**新UIの表示・Macro overlay・Smart Loudness・DynamicBass・Native HOT・LocalWarm・IR Warm/Budget**を個別に無効化できるようにする。単なる`false`定数でどこからも参照されないのは不合格。
- 全新機能OFFで旧v7 Profileの音質を完全再現。データストアの古いschemaを壊さない。Future schemaデータは読むことは許しても無断書換え・削除しない。
- Feature disabled状態からenabledに戻した時、未保存draftを勝手にrestore/commitしない。ON/Off, Playback/No Media, Format changeを対象にE2E試験。
- 本番で確認された重大問題には`Disable enhanced DSP`と`Restore saved profile`導線。緊急無効化がCarPlay Sessionの再接続を要求しない。

---

## N. レビューとGitHub運用規則

### N1. PR #52を正しいベースのまま修正する

```bash
# 作業前に常に最新の状態・同期状態を検査
git status --short
git fetch origin
git branch -r | grep -E 'dsp-studio-final|step4-pr-00-test-baseline'
git merge-base --is-ancestor origin/codex/step4-pr-00-test-baseline HEAD
# 作業branchが想定と異なるときは破壊的checkoutせず、最新差分を調べる
```

- 既存PR #52はPR #45 branchをbaseとするStacked PR。修正は`codex/dsp-studio-final`へ小コミットで追加。baseを`main`に切り替えない。
- PR #45の内容に新しい差分が来たら`git log`/`git diff`で衝突、DSP Interface、Android/CarPlay/USB regressionを確認し、必要ならレビュー用別commitで統合。勝手にforce-push/rebaseしない。
- **Luna subagent実行が可能な場合:** 互いに所有ファイルが競合しない作業を独立worktree+子PRで並走。Lunaの名前/モデルの実在と制御方法は事前検証。配布されていないAPI・モデルを作ったことにしない。
- 子PRのCI+独立レビューPASS後に統合branchへmerge、PR #52の最終headで全testを再実行。独立PRのみGREENで統合PRをPASSにしない。
- 既存不具合・新不具合が残る場合、PR本文にCritical/Open issuesを常時記載。完了前にDraft→Ready/mergeを自動変更しない。

### N2. 4つの完全に独立したレビュー

| Review Track | オーナー境界 | 必須確認 |
|---|---|---|
| R1 Native/audio signal | dsp_graph/engine, macro, loudness, dynamic bass, EQ response, Headroom | freq response, signal order, detector tap, envelope, clip, phase, latency, golden, ABI, SAN, perf |
| R2 Kotlin/JNI/lifecycle | ProfileRuntime, Coordinator, UpdatePlanner, AndroidMediaSink, NativeProcessor | generation, owner fencing, preview/save, close/rebind, concurrent updates, last good, no thread blockage |
| R3 UI/UX & Persistence | 5 tabs, EQ gestures, Module card, Presets, Voice/Volume | full/compact, all modules reachability, gesture transaction, A/B scope, state, accessibility, field migration, Factory/User/IR |
| R4 Integration QA/security | CI, build, Legacy/CarPlay/USB, test matrix | 110+cases evidence, test skips, Release status, regression, File traversal, PII, Surface/touch/session, hardware gates |

- **独立レビュー**は実装担当とは別のコンテキストで実施。各Findingに`ID, Severity(P0/P1/P2), Evidence, file:line@SHA, Reproduction, Root cause, Fix commit, Regression test, Retest SHA, Disposition`を記載。
- P0/P1指摘は必ず修正・再テスト・再レビューし、`fixed in source`と`verified test`を分ける。元の報告に「No open source-correctable P0/P1」とあっても、後続CIのFAILは再判定する。
- CI/実機/レビュー準備を積み残したまま「完全実装」と表現しない。PRレビュー本文に`Implemented / Implemented but not verified / Blocked hardware / Not implemented / Tested pass`の5状態を明記。

### N3. 必ず生成・更新するリポジトリ内ファイル

```text
docs/dsp-studio/
  REMEDIATION_SPEC.md             # 本書をリポジトリへ保存するならこの名前
  CURRENT_BASELINE.md             # 各開始/完了commit・PR構造・CI URL・環境
  STATE_POLICY.md                 # profile/workspace/save/recreation/A-B契約
  PHASE01_05_ACCEPTANCE.md        # Phaseごとの契約/結果/証拠リンク
  LEGACY_FIELD_COVERAGE.md        # 旧設定→5タブmap全件
  MODULE_HOT_WARM_MATRIX.md       # フィールド別update区分/実証
  TEST_MATRIX_DETAILED.md         # 110本+追加PHテストを**個別行**
  CI_FAILURE_INVESTIGATION.md     # Actions#40249136921,#40250193181と最新Run
  REVIEW_MATRIX.md               # 4独立レビュー/指摘、Fix/Retest
  CHANGE_LEDGER.md                # Commit/影響/根拠/テスト
  HARDWARE_RUNBOOK.md             # 車載機/iPhone操作・ログ/判定
  FINAL_IMPLEMENTATION_REPORT.md # 過去レポートを上書き更新
  ROLLBACK.md                     # Feature flags/旧状態復旧
```

存在する既存ドキュメントを**重複増殖せず更新**してもよい。`TEST_MATRIX.md`に既に一括行があるなら、詳細行リンクを持たせる。

### N4. レビュー最終レポートの固定フォーマット

```markdown
# DSP Studio Remediation Final Report
- PR: https://github.com/choko510/DiPlay/pull/52
- base branch/SHA: ...
- head branch/SHA: ...
- Diff summary: N files / commits / module categories
- Phase 01: DONE/PARTIAL/NOT_DONE; evidence ...
- Phase 02: DONE/PARTIAL/NOT_DONE; evidence ...
- Phase 03: DONE/PARTIAL/NOT_DONE; evidence ...
- Phase 04: DONE/PARTIAL/NOT_DONE; evidence ...
- Phase 05: DONE/PARTIAL/NOT_DONE; evidence ...
- Macro / Smart Loudness / Dynamic Bass: DONE/PARTIAL/NOT_DONE; evidence ...
- Phase 07: HOT verified fields, LOCAL_WARM verified fields, deferred fields, reason ...
- Changed APIs and compatibility/rollback impact: ...
- Native build / sanitizer / JNI / Android variants / unit / instrumented / benchmark: exact commands, counts, exit codes, URLs
- 110 test catalog: PASS / FAIL / NOT_RUN / BLOCKED_ENV / SIMULATED_PASS, all with SHA
- CI: check, host-baseline, instrumentation, Release verification and skipped job list
- 4 independent reviews: open P0, P1, P2; retest SHA and proof
- Hardware: TS10/iPhone NOT_RUN or evidence; exact host/AV/session identity if available
- Rollback proof: flags OFF, v7 custom1/custom2, DSP Master OFF new Track exact PCM
- DECISION:
  IMPLEMENTED_REVIEW_READY: true/false
  CI_GREEN: true/false
  HARDWARE_PASS: true/false
  MERGE_READY: true/false
  REASON: ...
```

---

## O. 最終GO/NO-GOゲート（途中終了禁止、ただし安全な限界を明示）

**`IMPLEMENTED_REVIEW_READY=true`の必要条件:** Phase 01–05各機能が本番UI→State→Rendererまで動作、CR-001..023のソース修正または安全な明示的DEFERが検証済み。CR-024は実機不在ならHARDWARE_NOT_RUNとRunbookを残す（ハードウェア結果はレビュー準備判定に混同しない）、関連Unit/Native/Instrumentedが最終HEADでPASS、保存互換/Feature OFF rollbackが成立、T-ID全件の行別証拠がある、4 reviewerのOpen P0/P1=0。

**`CI_GREEN=true`:** GitHub Actionsの最新HEADに対応する全必須Jobがsuccess、選択/skip理由を記録。`Release verification`がfailureなのにtrueは禁止。性能テストのFAILは合格まで修正/原因判定が必要。

**`HARDWARE_PASS=true`:** TS10/K706（または明示的に合意した対象ヘッドユニット）とiPhone実機で55:45 20回・Session/Surface/Touch、A/V Clock、Siri/Call、Volume AUTO applicability、30～60分soak、音質、CPU/RSS/underrunを測定し事前定義ゲートを通過。ハードウェアがなければfalseのまま。

**`MERGE_READY=true`:** 上の3判定がすべて真で、Review open P0/P1 0、破壊的/未合意変更無し、ロールバック確認済み、ユーザーの最終承認がある。Codexが勝手にmainへmergeしない。

**途中に出来ない項目が発生した場合:** 実装可能な他の箇所は止まらず進め、Blocked reasonと再現手順を残す。`HARDWARE_NOT_RUN`は正当な状態だが、**基本ソースの未実装・既知CI失敗・UI未接続を隠す免罪符にしない。**



---

> **Codex作業中の注意:** 以下はPR #45ベースの最終仕様に含まれていたテストカタログを、**省略せず丸ごと再掲**したもの。今回追加した`PH01–PH07-Txx`とは別の照合用IDであり、同一テストメソッドが両方を満たしてもよいが、`TEST_MATRIX_DETAILED.md`では各IDの`Test Method/SHA/Result/Evidence`を個別行で残す。各IDの要求はコード内のJUnit/Native/InstrumentedテストあるいはHardware Runbookで証明し、出力証拠が無ければPASSにしない。廃止/延期するテストがある場合はユーザーへ残留リスクとして明示し、必須条件を勝手に減らさない。

## P. 既存の正式T-ID 110テスト：元の最終実装MDから全件保持

全テストは次のどれかにマップする：Kotlin Unit / Robolectric / Native Host / Native ASan+UBSan / Android Instrumented / TS10 manual / A/V benchmark。テスト名にIDを含めるか、レビュー台帳で`ID → testMethod → status → SHA`をリンクする。

### P.1 Macro：`T-MAC`

| ID | 入力・操作 | 必須期待値 |
|---|---|---|
| T-MAC-01 | 新規インストール/旧v7からロード | Macro Disabled、50/50/50/50。既存音質に変化なし |
| T-MAC-02 | 各マクロ0/50/100 | 非有限値なし、Neutral=補正なし、UIへ正しい内訳 |
| T-MAC-03 | Macro Bass/Vocal/Air変更 | 元の`eqBands`,`bass`,`cabinCorrection`などBaseProfileは完全一致 |
| T-MAC-04 | Width操作、mono/stereo、旧AdvancedMS ON/OFF | mono passthrough、左右チャンネル順・極性を壊さない、既存Width値不変 |
| T-MAC-05 | Factory選択→手動編集→Factory再選択 | Factoryは不変、編集はCustom unsavedとなり、他モジュールは変えない |
| T-MAC-06 | 100回の高速ドラッグ/Undo/Neutral/プレビューCancel | 最新値のみAudioでACTIVE、古いACK/UI callback無効、保存値維持 |

### P.2 Module Preset：`T-PRE`

| ID | 入力・操作 | 必須期待値 |
|---|---|---|
| T-PRE-01 | EQだけFactory選択 | EQだけ差替え、他の設定・IR参照・Macro unchanged |
| T-PRE-02 | Bass/AdvancedMS/Compressor/Multiband等各Factory | 選択したmodule fieldだけ変更。入出力schema妥当 |
| T-PRE-03 | User Preset保存→再起動→復元 | 全Module round-trip成功、名前・安定ID維持 |
| T-PRE-04 | 保存I/O失敗/途中クラッシュ | 旧ファイル破損なし、偽の保存成功なし |
| T-PRE-05 | future `moduleSchema=99` | UIへ互換性なしと通知、ファイル無断上書き/削除なし |
| T-PRE-06 | 変造ID、`../`、非常に長いファイル/JSON、NaN/Infinity、unknown enum | fail-closed、path traversal/DoSなし |
| T-PRE-07 | EQ14+Cabin2、異常Freq/Q/Gain | 15Band容量越えを拒否、既存bandを削除しない |
| T-PRE-08 | IR Preset保存→IR削除/破損/重複使用 | 正しい参照依存・エラーメッセージ、他PresetのIR生存 |
| T-PRE-09 | A/B・Undo・Factory Reset・各モジュールOFF | 他module/FULL Master状態を壊さず、音声Ack整合 |

### P.3 Smart Loudness：`T-LOUD`

| ID | 入力・操作 | 必須期待値 |
|---|---|---|
| T-LOUD-01 | OFF/strength0/volume一定 | 補正0、PCM/Headroomの不要変化なし |
| T-LOUD-02 | calibrated listening level低→高 | 低音量ほど補正が大きい単調性、上限内 |
| T-LOUD-03 | 音量変更なしで楽曲RMS/Peakを変更 | Loudness gainは楽曲RMSだけでは変動しない |
| T-LOUD-04 | AudioManager index変化なし/MCUノブだけ変化 | AUTO unavailable、実音量検出済みと誤表示しない |
| T-LOUD-05 | `isVolumeFixed()`、unknown route、例外、NaN/-Inf、0最大index | 安全fallback、Nativeへ非有限を送らない |
| T-LOUD-06 | volume 0/Max、音量キー高速連打、オーディオ/route世代変更 | Smooth ramp/Clipなし、古いroute calibrationを適用しない |
| T-LOUD-07 | MANUAL_ESTIMATEスライダー | 音量推定を補正へ反映し、実ハード音量を変更しないことを明示 |
| T-LOUD-08 | FIXED補正 | Auto入力が消えても固定値を維持、UIが固定表示 |
| T-LOUD-09 | Calibration JSONの不正/未来schema/route mismatch | 旧値保持、停止/再校正導線、安全にOFF/MANUALへ |
| T-LOUD-10 | AUTO校正後の音量変化 + SmartLoudness ON/OFF 20回 | 追加AlgorithmicLatency 0、実機測定またはNOT_RUN |

### P.4 Dynamic Bass：`T-DB`

| ID | 入力・操作 | 必須期待値 |
|---|---|---|
| T-DB-01 | OFF/strength0/maxBoost0 | 追加補正なし、旧音質Goldens一致 |
| T-DB-02 | 完全無音（1分以上）、低レベルノイズ | Gateで増幅なし、数値有限、遅延0 |
| T-DB-03 | Bassレベル弱→強の入力 | 強い低域ほどBoostが減少、上限0..maxBoost |
| T-DB-04 | 低域強→弱→無音→復帰 | Attack/Release、Gate、Holdが安定しポンピング/クリックを回避 |
| T-DB-05 | 1発のKick/連続808/瞬間Full-scale低域 | ゲイン抑制が追いつく、Clipや激しいovershootなし |
| T-DB-06 | 左のみ/右のみ/逆相/mono | 左右GainはStereo Linked、極性/定位の意図しない崩れなし |
| T-DB-07 | サンプルRate44.1/48k ×mono/stereo | フィルタ安定/有限値/応答許容。8k/192kもvalidationの境界試験 |
| T-DB-08 | 全長まとめ処理 vs1/127/128/512/1024/不等長分割 | 同一信号の出力が境界に依存しない（許容誤差仕様記載） |
| T-DB-09 | Preboost信号を固定して後段Bassを変化 | Detector gainが後段Bassのfeedbackで揺れない |
| T-DB-10 | Compressor+Limiter+EQ+Harmonic+AdvancedMSとの組合せ | Headroom budgetとGR評価、ステレオチャネル/音質破壊なし |
| T-DB-11 | Dynamic BassのHot設定100回変更 | Detector envelopeは不要にresetされず、最後のGainへsmooth収束 |
| T-DB-12 | PCM format変更・AudioRenderer close/reopen・Master OFF/ON | 正しいreset、古いowner/handleのupdateなし、メモリリークなし |

### P.5 Headroom / Safety：`T-SAFE`

| ID | 条件 | 必須期待値 |
|---|---|---|
| T-SAFE-01 | 手動Bass+Macro+Loudness+DynamicBass各Max | 合計Budget超過を検知/制限・理由表示、数値有限 |
| T-SAFE-02 | 既存15Band + Cabin + Harmonic + M/S + Compressor Makeup | 既存`DspAutoHeadroom`の保護を退行させない |
| T-SAFE-03 | メインPreamp負/正、AutoHeadroom ON/OFF | 意図しないクリップ・急なGain跳躍なし。危険設定は警告 |
| T-SAFE-04 | Hot commandでBass強化とPreamp/GR同時変更 | すべて同世代で確定、短い過大Gainフレームなし |
| T-SAFE-05 | 無効/異常Native command | 旧良好設定維持、エラーを報告、Native memory不変 |
| T-SAFE-06 | Loudness volumeを小→大→小繰返し | Macro/DBのBudget内に常時収まり、Preampが不必要に踊らない |
| T-SAFE-07 | Input Peak 0.001/0.05/0.45/0.9で全機能ON | 限定条件でSamplePeak/TruePeak/THD測定、異常増幅をしない |
| T-SAFE-08 | Limiterしきい値/GR連続測定 | Constant GRを音質劣化として警告。SamplePeakをTruePeakと誤表示しない |
| T-SAFE-09 | Output/内部計算がNaN/Inf/denormalの入力 | サンプル安全化、エラー報告、無限ループ/メモリ破壊なし |

### P.6 ライブ更新・世代管理：`T-LIVE`

| ID | 操作 | 必須期待値 |
|---|---|---|
| T-LIVE-01 | Gain/Macro変更中の再生 | AudioTrack再作成/CarPlay再接続なし、処理音が変わる |
| T-LIVE-02 | 100回ドラッグ、末尾flush | 最新値が最終ACTIVE。取り残しなし |
| T-LIVE-03 | Native Hot受信中にRenderer close | 解放済Handle参照/UAF/double-freeなし |
| T-LIVE-04 | Format 44.1→48k / stereo→mono | 旧format command reject、安全な新設定移行 |
| T-LIVE-05 | editorOwner/rendererOwner generation変更 | 旧callback/古いCommandが現行設定を壊さない |
| T-LIVE-06 | Hot+WholeGraph準備同時 | 同じ世代で安全に同期、旧Graph採用によるHot巻き戻りなし |
| T-LIVE-07 | Hotコマンドが不正/非finite | Fail-closed、LastGood保持、UI FAILED |
| T-LIVE-08 | 値変更→RAMPING→ACTIVE | 「LIVE」は該当実処理ブロックを通したACK後のみ |
| T-LIVE-09 | 再生MEDIAなしのUI操作 | UI値更新可、`NO_MEDIA`表示、ACTIVEと偽称しない |
| T-LIVE-10 | A/B高頻度切替とUndo | Old ACKはignored、保存プロファイルを壊さない |
| T-LIVE-11 | DSP Master OFF→ON mid-track | 既存latency-preserving behavior維持、次Trackで旧bit-exact OFF可 |
| T-LIVE-12 | 既存Compressor+ConvolverなどstatefulがONでMacroだけ変える | 変更はHOT、Graph全面再生成・DEFERを発生させない |
| T-LIVE-13 | statefulがONでPEQのみ変える | 対応済み局所WarmならLIVE、未対応なら理由付きDEFER。虚偽ACTIVEなし |
| T-LIVE-14 | 未保存Preview→Cancel/Activity再生成 | Saved stateから新generationで復元。古いcallback/dirty混線なし |
| T-LIVE-15 | dynamicEQ/maxGain Hot vs filterFreq Warm | 実際のstate維持/暖機/Deferred条件を分類 |
| T-LIVE-16 | Multiband crossover/AdvancedMS crossover | LR phase/mono安全、Warm不合格時旧Graph維持 |
| T-LIVE-17 | Harmonic quality/Convolver IR/latency change | 安全条件のみWARM、不足ならDEFER/Reason、Track強制再作成しない |
| T-LIVE-18 | 旧pipeline retirement中に新candidate publish | Reaper owner/queue、retirement失敗時更新停止・報告 |
| T-LIVE-19 | Render status REQUESTED→PREPARING→PREPARED→ACTIVE/DEFERRED/FAILED | 異常逆行なし、同じgenerationとownerのStatusのみ採用 |

### P.7 UI/CarPlay：`T-UI`

| ID | 操作 | 必須期待値 |
|---|---|---|
| T-UI-01 | FULL Studioで4MacroとFactoryセット | 主役UIが操作でき、値/中立/保存/状態が一致 |
| T-UI-02 | 55:45 Split、狭い横幅/異なる文字サイズ | 主要マクロにアクセス可、長大な縦スクロールなし |
| T-UI-03 | 15Band EQ Canvas dragとQ/Type編集 | 表示カーブ/実パラメータ/操作対象一致、バンド容量守る |
| T-UI-04 | Module cardのプリセット/詳細開閉 | 他Module不変、状態も見える、タブ遷移維持 |
| T-UI-05 | Undo/A-B/Cancel/Save | dirty/renderer status/save statusが混ざらない |
| T-UI-06 | Volume AUTO unavailable/Saved/Loaded | ラベル正確、手動/固定 fallback操作できる |
| T-UI-07 | keyboard/number入力/rotation/large font | 音声/映像停止なし、IMEでCarPlayタッチ崩れなし |
| T-UI-08 | legacy全項目のアクセス性 | 旧機能に到達できない項目なし（移行チェックリスト） |
| T-UI-09 | Accessibility/ContentDescription/外部キー入力 | focus order/操作可能性、危険操作の確認文言 |
| T-UI-10 | Full↔DSP Splitを20回連続 | CarPlay session/Surface ownership/MediaSink/touch mapperが維持、再接続なし |
| T-UI-11 | 20回切替中Macro連続調整、Activity再生成、音声route切替 | crash/音声反映取り違え、古いSession callbackなし |

### P.8 後方互換と保存：`T-REG`, `T-MIG`

| ID | 必須試験 |
|---|---|
| T-REG-01 | Master OFF+新Trackでlegacy PCM16 byte-for-byte（金テスト） |
| T-REG-02 | Live Master OFFは現行Latency保存、次Trackでbit-exactを回復 |
| T-REG-03 | MEDIA以外（Phone/Siri/Nav等）に新3機能のGraphなし |
| T-REG-04 | 新Macro/Loudness/DynamicBass disabled/neutralで旧Graphの処理・DSP値同一 |
| T-REG-05 | 44.1kHz/48kHz × mono/stereo ×8種類ON/OFF組合せのPCM/遅延/状態確認 |
| T-REG-06 | 各PCM block分割でfloat/PCM16の同じ出力（従来差分許容と比較） |
| T-REG-07 | Existing Dynamic EQ 5band/PEQ+Cabin 15band容量に新3機能が食い込まない |
| T-REG-08 | Seat左右Trim/反転/距離/Mono Bass/M/S/Harmonic/Convolverの既存挙動を維持 |
| T-REG-09 | Native handle create/process/reset/close + generation不一致 |
| T-REG-10 | PlaybackClock/RTP/AudioTrack latencyとA/Vを維持 |
| T-REG-11 | Native old field default vs new field defaultのABI/構造体長/JNI validation |
| T-REG-12 | CarPlay Video/TextureView/Surface/touch/Session/Audio Stream ownerの退行なし |
| T-MIG-01 | Factory v7/v0～v7 profileの読み込み成功・旧音質一致 |
| T-MIG-02 | `custom1`,`custom2`旧schemaと既存`selected_profile_id`維持 |
| T-MIG-03 | 新Workspace未存在時に自動作成/予期せぬcommitをしない |
| T-MIG-04 | Macro disabled/Neutral、Loudness OFF、DynamicBass OFFが移行default |
| T-MIG-05 | Workspace save→process restart→再ロードで完全RoundTrip |
| T-MIG-06 | Save失敗/AtomicFile rollback/電源断想定/空ファイル/部分JSON |
| T-MIG-07 | Future schema各種が読み取り結果として保持され既存ファイルを上書きしない |
| T-MIG-08 | IR参照/孤立判定/Module preset/A-B/Workspace保存の依存関係 |
| T-MIG-09 | 設定保存前のPreviewだけでアプリ再起動しても旧保存音質 |
| T-MIG-10 | Feature flags OFFによる旧実装へ切替後、旧v7プロファイルで起動可能 |

### P.9 パフォーマンス/実機：`T-PERF`

| ID | メトリクス・方式 | 合格判定 |
|---|---|---|
| T-PERF-01 | 変更前旧DSP FULL Heavy baseline p50/p95/p99、割当、CPU | baseline数値・機器・SHA/設定/ブロックサイズを保存 |
| T-PERF-02 | 新Stage OFF/Neutral時のoverhead | 旧経路と出力同一、過度な追加CPUなし |
| T-PERF-03 | Hot50～100回/secのcontrol storm | Audio callback allocations0/lockなし、最新値採用 |
| T-PERF-04 | Macro単独/SmartLoudness単独/DynamicBass単独 p99 | block deadlineに収まる、端末差を記録 |
| T-PERF-05 | 3機能同時、既存FullHeavyChain | baselineとのp99差/CPU/メモリ/Native state記録 |
| T-PERF-06 | Spectrum/gain UI telemetry 5/10Hz ON/OFF | Audio thread低負荷、UI無限recomposeなし |
| T-PERF-07 | 44.1/48 ×mono/stereo ×PCM不等ブロック | レンダラ実時間/中断/クロック異常なし |
| T-PERF-08 | Warm shadow EQ/IR ON/OFF、Retirement | ピークメモリ、CPU、free/GC、rollback原因を記録 |
| T-PERF-09 | TS10 30～60分Media連続 + 操作 | underrun/クラッシュ/遅延増加なし。未実施はNOT_RUN |
| T-PERF-10 | TS10物理ノブ/Android Stream音量 correspondence | 信頼できる場合のみAUTO。未実施はAUTO NO_GO |
| T-PERF-11 | A/V sync、Split20往復、Siri/通話/Nav別Stream | 旧baselineから悪化なし、未実施はNO_GO |
| T-PERF-12 | ASan/UBSan、JNI mapping、benchmark app build、release smoke | PASS/BLOCKED_ENV/NOT_RUNの事実を記録 |

---

---

## Q. 最終命令・Codexが着手直後に行う具体的な10操作

1. 最新PR #52 head、PR #45 base、60変更ファイルと新増分、CI完了状況を再取得。`ba6b6592`の固定情報から前進したら差分を記録し、既に別担当が修正した内容を二重作成しない。
2. `git status`, `git diff`、AGENTS.md、`.scratchpad`、既存docs/step4とdocs/dsp-studioを参照。未コミットコードは絶対に破棄しない。
3. `CI_FAILURE_INVESTIGATION.md`へActions #40249136921と#40250193181のNative benchmark failureを再現・分離し、最新テストの真の状態を記載。`UI PASS`と`Native PERF FAIL`を別に記載。
4. `LEGACY_FIELD_COVERAGE.md`の全旧DSP fieldsを列挙し、5タブの対応を決める。5タブのナビゲーションを最初に**本番で起動可能**な形にする。
5. `DspStudioCoordinator`/EditSession/Save/CancelのSingle Source of Truthを完成。保存WorkspaceとlegacyProfile IDの不整合、Activity再生成、UI反映の非決定性を修正。
6. Preset削除・Factory Reset・IR参照とScoped A/B、gesture undoを実装。必ず他module不変テストを添える。
7. EQ Canvas pointer drag＋numeric editor＋実sample rate＋理論カーブ検証を実装。`DspEqDesigner`とNativeの伝達関数を比較。
8. Macro/Loudness/DB→Renderer HOTを**操作中の自動更新**へ接続。必要ならNative ABI拡張。Smart Loudness playback owner、Dynamic Bass preboost tap、Headroomを一貫して修正。
9. 既存DSPをfield-diff HOT/WARM_LOCAL/DEFERへ移行し、PEQ/Bass/Preamp/Compressorなどの安全なライブ反映を完成。IR/Latencyは無理に切替えない。
10. Phase01–05とPhase07のテスト、全110正式T-IDと60追加PH-ID、Native sanitizer、Android CIと4独立レビューを回し、最終PR #52へ**コード・テスト・証拠・残課題**をPush。マージせず終了。

**完了報告はこの一文を満たすまで認めない：**「Phase01–05と新3機能が、実際の本番UIからDSPのRendererまでつながり、保存/比較/取消しが正しく動き、最新HEADで実行可能なテストがPASSし、実機未検証項目は明確に保留され、PR #52に再現可能な証跡が残っている」。

**報告文の書き方:** 実装していない機能は`NOT_IMPLEMENTED`、コードがあるが本番で未接続なら`NOT_WIRED`、実行していない試験は`NOT_RUN`、端末/SDKが無ければ`BLOCKED_ENV`、モック上で通っただけなら`SIMULATED_PASS`、CI実行で失敗なら`FAIL`。あいまいな「概ねできた」「一応完了」「テストは大体成功」を避ける。
