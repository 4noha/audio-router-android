# audio-router-android

macOS の [AudioRouter](https://github.com/4noha/audio-router) のコンパニオン Android アプリ。
同じ Wi-Fi にいる Mac を自動で見つけて、スマートフォンのマイク音を Opus over UDP で
Mac へ送り（AudioRouter の「ワイヤレスマイク」）、Mac からの戻し（任意のミックスや
効果音）を受けて再生する。

## 必要環境

- Android 10 (API 29) 以上（MediaCodec の Opus に必要）
- Mac と同じ Wi-Fi
- Mac 側で AudioRouter が起動していること
  - AudioRouter は起動すると Bonjour `_4noha-audio._udp` を公開し、UDP 47391 で待ち受ける

## 使い方

1. Mac で AudioRouter を起動する。
2. 本アプリを起動し、マイク権限と通知権限を許可する。
3. Mac が見つかると「接続先 Mac」に並び、最初に見つかった Mac へ自動で接続する。
   別の Mac にしたいときはリストから選ぶ。見つからないときは「再スキャン」。
4. Mac の AudioRouter に、端末名の付いた「ワイヤレスマイク」の行が自動で追加される。
   スロット（1..4）は Mac が端末名で割り当てるので、複数台でもそのまま使える。
5. 戻し（Mac → Android）は、Mac 側で「ネットスピーカー」（`netSpeaker`）を送り先にした
   行を有効にすると再生される。

アプリを閉じても、通知が出ている間は送受信を続ける（Foreground Service）。
止めるときは通知の「停止」を押す。

## プロトコル

Mac 側の [`NetAudioProtocol.swift`](https://github.com/4noha/audio-router/blob/main/Sources/AudioRouterCore/NetAudioProtocol.swift)
が正。22 byte のヘッダ（magic `"4NHA"`、big-endian）＋ payload を UDP で送る。
Android 側の実装は `app/src/main/java/com/fournoha/audiorouter/NetAudio.kt`。

## ビルド

JDK 17 と Android SDK が要る。Android Studio で開いて Run するか:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## ライセンス

MIT License（`LICENSE`）。
