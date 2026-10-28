# PR #52 独立コード再監査 指摘対応報告

対象PRは [#52](https://github.com/choko510/DiPlay/pull/52)、指摘対象のHEADは `f40d51ea82f6129c1455f1ae533de526633025f8`。F-01/F-02修正は `a0cb2d560dfd409bd9500b1d5d39371a0c1b12a4` と、同じ指摘をさらに補強した `f5673dd7a9d7d488c63d2a620227527e4c4978ae` に記録した。

## F-01 — Studioゲイン返金のサンプル不連続

- 適応Headroomのゲイン返金を、モジュールが既にsettledの場合と、Macro／Smart Loudness／Dynamic Bassの遷移後に開始する場合の両方で20 msのサンプルランプにした。
- ランプはdouble累積値で進め、最終サンプルで指定された線形ゲインへ正確に着地する。floatの累積加算誤差による微小な目標未達を防ぐ。
- `dsp_graph_hot_transition_pending()`がランプ完了までpendingを返すため、Native HOT ACKはランプ中に確定しない。新しい世代で旧更新が上書きされた場合、旧世代をACKしない。
- Native回帰テストは44.1/48 kHz、mono/stereoで、遷移待ちと即時適用を別々に検証する。サイン波、インパルス、決定的ノイズを入力し、ゲインの各サンプル差、単調性、overshoot、最終目標、Limiterのgain reductionとsample-peak上限を確認する。追加ケースでCompressor makeupとDynamic EQの低減中も返金を待つ。

## F-02 — Renderer所有者が消えた状態のCancel誤ACK

- 最初からRenderer所有者がいないrequestは、従来どおり次のtrackからの適用準備完了として扱う。
- owned requestはstatus消失、status側のowner欠落、またはrequestより古いownerでは `WAITING` とし、timeout時は `REJECTED` にする。
- Restore要求開始時のowner tokenを保持し、非同期restore中にstatusが消えてもowned requestをownerlessへ変えない。
- 同一ownerまたは新しいownerは、Committed workspaceと対象generationの `ACTIVE` が確認できた場合だけACKできる。古いgeneration、`DEFERRED`、`FAILED`、timeoutは成功扱いしない。
- 回帰テストはstatus消失／owner欠落、古いowner、新ownerの未適用・旧generation・対象generation、新旧ACTIVE、Committed状態、`FAILED`／`DEFERRED`、timeoutを確認する。

## 検証結果

- 修正前の回帰テストでF-01の返金ランプ未開始と、F-02のstatus消失・古いownerによるfalse `APPLIED`を再現した。
- 修正後の `scripts/test_dsp_native.sh` 成功。Native DSPとDynamic EQ HOT両suiteがpass。
- 統合後の `:shared:testDebugUnitTest` 734/734、`:common:testDebugUnitTest` 357/357、failure/error/skip 0。
- `:mobile:lintDebug`、`:automotive:lintDebug`、両アプリのDebug/Benchmark assemble成功。JNI/R8 mapping check成功。
- ローカル `scripts/test_dsp_native.sh --sanitize` はMSYS2 GCCに `libasan`／`libubsan` がなくリンク不可。Hosted run [#581](https://github.com/choko510/DiPlay/actions/runs/40719476119) は先行head `a0cb2d56` で成功し、final head `f5673dd7` は [run #583](https://github.com/choko510/DiPlay/actions/runs/40722523549) で `changes`, evidence, Native, ASan/UBSan, Shared/Common, lint, Debug/Benchmark, JNI/R8, API 35 instrumentation, `host-baseline` が成功。API 35 Shared Release 23/23、Common Debug 29/29。Artifacts: [check](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12830824159), [instrumentation](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12831597236)。`quick-baseline`とRelease verificationはworkflow_dispatchでskipされ、PASS扱いしていない。PR-triggered #582はworkflow concurrencyによりcancelledで、成功runとして数えない。

## 未検証の受入項目

- この修正で170行の受入台帳のstatusは変更していない。現行集計はPASS 2 / PARTIAL 162 / NOT_RUN 6のまま。
- API 35 emulator instrumentationはrun #583でpassした。TS10/K706＋iPhoneの音声・USB・Surface・Touch、route volume、A/V、CPU/RSS、20回再接続、soakは未検証。Emulatorの結果を実機検査の代替とはしない。
- PR #52はOpenのまま。マージは行っていない。
