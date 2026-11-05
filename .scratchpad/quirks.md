# 現在も有用な環境上の注意

- **Windows/SDK:** `local.properties` がないcheckoutは `ANDROID_HOME`/`ANDROID_SDK_ROOT` を実際のSDKへ向ける。必要ならAndroid Studio JBRを `JAVA_HOME` に指定。個人環境の絶対パスはコミットしない。
- **Java/Robolectric:** Windows Java 25では一部テストが `libandroid_runtime.dll` access deniedになった。確認済みJBR 21を使う。Gradle daemonの一時設定変更は必ず戻す。
- **Bash/native:** PATHの `bash` が動かないWSL shimの環境ではGit Bash/MSYS2 UCRTを明示。ローカルMSYS2の `libasan`/`libubsan` 不在は `BLOCKED_ENV` とし、Linux CIのsanitizer結果で確認する。
- **行末:** Windows `core.autocrlf=true` で固定EX-08 JSONL/CSVがCRLFとなり、バイトhashが不一致になることがある。コミットされたLF内容と照合し、台帳を書き換えてごまかさない。
- **API 36.1 Espresso:** 2026-10-31時点でCompose interactionが `InputManager.getInstance()` 不在の初期化段階で停止。テストメソッド未実行のため製品不合格とはしない。API 35で実行する。
- **Emulator/計測:** 大きなandroidTest APKには十分なdata partitionが必要。高負荷並列ビルド時のwall p99を単独のthread CPU p99と混同しない。
- **AtomicFile:** Windows Robolectricのrenameが既存ファイルを置換しないケースあり。ホストテストのwriter注入とAndroid実機instrumentationの永続化検証を区別する。
- **MFi:** `DIPLAY_AUTH_ASSETS_DIR` は `offline-mfi` 自体ではなくその親を指す。秘密の認証資産をGitに含めない。
- **署名:** release鍵がない場合 `validateSigningRelease` はfail closed。debug署名による入替を製品署名・rollback成功と扱わない。
- **Actions:** docs-only・Draft・FROM-CACHE・cancel/retryを確認し、runの `head_sha` と実行済みtaskだけをPASSの根拠にする。
