# CLAUDE.md — audio-router-android

Android アプリ。Mac の AudioRouter に LAN で接続してマイク音声を送信、
Mac からの戻し（任意ミックス・効果音）を受信して再生するコンパニオン。

> **親ワークスペース**: `~/works/4noha-studio-family/`（東雲はつりの制作・
> 配信ツール群アンブレラ）。配信側パイプライン全体図と ARPS v1 共有メモリ
> 契約は親 `CLAUDE.md` / `README.md` を参照。本リポジトリは Mac 側
> `audio-router` の対向。プロトコル仕様は Mac 側の
> `audio-router/Sources/AudioRouterCore/NetAudioProtocol.swift` が権威。

## アーキテクチャ

```
MainActivity            権限取得 → 発見 → 最初に確認できた Mac へ自動接続（接続ボタンなし）
  ├─ NsdHelper          NSD で _4noha-audio._udp を browse / resolve → 候補 (host, port)
  ├─ PingVerifier       候補へ unicast PING を 2 秒毎。PONG が返った Mac だけを Spinner に並べる
  └─ AudioBridgeService Foreground Service (type=microphone)。通知の「停止」でのみ止まる
       └─ AudioBridge   1 つの DatagramSocket で送受信
            ├─ 送信     AudioRecord(mono 48k) → OpusEncoderWrap → UDP
            └─ 受信     UDP → OpusDecoderWrap → AudioTrack（Mac からの戻し）
NetAudio                22B header の encode/decode (Mac 側と byte 一致)
OpusCodec               MediaCodec の Opus encoder / decoder ラッパ
```

- すべて Kotlin、View ベース UI (ConstraintLayout)。
- Opus は **MediaCodec** (Android 10+ で対応)。VOIP モード相当の
  bitrate=32k で送出、受信は Mac の出力 (AUDIO mode, 128kbps) を decode。
- slot は Android からは選ばない。HELLO の payload（端末名 + `\0` + バージョン）
  を見て Mac が割当て、hello-ack の slot と端末専用 port（47391 + slot）で返す。
- UDP の socket は connect しない（多 NIC 端末で不適切な NIC に bind される
  のを避ける）。送信は毎回宛先を明示し、受信は送信元が接続先 Mac のものだけ通す。

## 規約・前提

- 言語: Kotlin、minSdk 29 (Android 10) / targetSdk 34
- マイク権限 `RECORD_AUDIO` 必須。起動時に runtime request。
- LAN ピア発見は NSD (NetworkServiceDiscovery)。multicast permission を
  manifest で要求。
- パケットフォーマットは Mac 側 `NetAudioProtocol.swift` と byte 一致:
  - 22B header (`"4NHA"` + ver + type + slot + codec + seq + ts_us + len)
  - payload: codec=opus なら Opus パケット、codec=pcm16le なら 16bit LE PCM
- 1 packet 1 frame、MTU 内 (≤1400 byte) に収める。

## ビルド / 検証

```bash
# Android Studio で開く
open -a "Android Studio" .

# CLI ビルド（JDK 17）
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### テスター配信（Firebase App Distribution）

```bash
./gradlew :app:assembleDebug :app:appDistributionUploadDebug
```

- App ID・配信先・service account JSON は**公開リポジトリに載せない**。
  `app/build.gradle.kts` が環境変数 → `local.properties`（git 管理外）→ `-P`
  の順に読む:
  | 環境変数 | `local.properties` のキー | 中身 |
  |---|---|---|
  | `FIREBASE_APP_ID` | `firebaseAppId` | Firebase の Android App ID |
  | `FIREBASE_TESTERS` | `firebaseTesters` | 配信先（カンマ区切り） |
  | `FIREBASE_APP_DISTRIBUTION_CREDS` | `firebaseCreds` | service account JSON のパス（既定はルートの `firebase-appdistribution.json`） |
- 未設定でもビルドは通り、配信タスクだけが失敗する。
- リリースノートは `release-notes.txt`。上げる前に `versionCode` / `versionName`
  と一緒に更新する。

## 落とし穴

- **Android の MediaCodec Opus は raw Opus packet (RFC 6716) を扱う**。
  OggOpus コンテナではない。Mac 側 (libopus) と直接やり取り可。
- **AudioRecord は Short (16bit PCM) を返す**。Float32 で送りたいなら
  自分で `/32768f` 正規化が必要。Mac 側 NetAudioServer は codec=pcm16le
  を Float32 化してリングに書くので、Android 側からは PCM16 で送るのが
  自然。
- **UDP は 1 packet ≤ 1400 byte を死守**。MTU を超えるとフラグメント or 
  drop。20ms (960 samples) PCM = 1920 byte だと既に MTU 超え。**PCM 直送
  は 10ms (960 byte)** に留めるか、Opus 圧縮を使う。
- NSD の resolve は async。接続中も browse は止めない（resolved
  endpoint が NAT/Wi-Fi で変わると再接続）。
- Wi-Fi 上の multicast は端末によっては節電で抑止される。`WifiManager
  .MulticastLock` を取って維持する。
- 戻し受信側で MediaCodec Opus decoder を毎フレーム 1 つ用意するのは
  非効率。1 decoder インスタンスを stream に使い回す。
