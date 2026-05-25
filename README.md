# App_Artify2 - Nano Banana 2 Style Editor

Android Studio の既存プロジェクトへマージするための、最小構成の Java/XML 実装です。写真フォルダから画像を 1 枚選択するか、その場で撮影し、画風を選ぶと、Gemini API の **Nano Banana 2** (`gemini-3.1-flash-image-preview`) に画像編集を依頼して結果を表示・保存します。

## 前提

- Java のみでアプリコードを実装
- XML Views (`activity_main.xml`)
- Kotlin DSL (`build.gradle.kts`) の既存 Android Studio プロジェクトへマージ
- Minimum SDK: API 23 (Android 6.0 Marshmallow)
- ネットワーク接続と Google AI Studio の paid API key が必要
- API キーは `MainActivity.java` の定数として指定するプロトタイプ構成
- 生成・編集画像には Google の SynthID watermark が含まれる

## 追加・置換するファイル

既存の Empty Views Activity プロジェクトを前提に、次のファイルだけをマージします。

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/app_artify2/MainActivity.java
app/src/main/java/com/example/app_artify2/NanoBananaApiClient.java
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
app/src/main/res/xml/file_paths.xml
```

このリポジトリでは package / namespace を `com.example.app_artify2` としています。Android Studio 側の namespace が異なる場合は、Java ファイルの `package` 宣言と格納ディレクトリ、manifest の activity 解決先をプロジェクト側に合わせてください。

`AndroidManifest.xml` は初期プロジェクトの launcher icon と theme が存在する前提の例です。プロジェクト側の icon / theme / backup 属性名が異なる場合は、それらを保持したまま `INTERNET` permission、API 28 以下でのみ使用する `WRITE_EXTERNAL_STORAGE` permission、`MainActivity`、Photo Picker 用 `service`、撮影用 `FileProvider` をマージしてください。

HTTPS 通信は `INTERNET` permission と `HttpsURLConnection` により行います。manifest では `android:usesCleartextTraffic="false"` を設定し、暗号化されていない HTTP 通信を許可しない構成にしています。Gemini API はシステムが信頼する証明書を使う HTTPS endpoint のため、追加の network security resource は不要です。

## Gradle 確認

新しいネットワークライブラリは不要です。API 呼び出しは Android 標準の `HttpsURLConnection` と `org.json` で行います。

Photo Picker の Java API を利用するため、既存の `app/build.gradle.kts` に AndroidX Activity がない場合のみ追加してください。

```kotlin
dependencies {
    implementation("androidx.activity:activity:1.13.0")
}
```

既存プロジェクトの `minSdk` は次の値であることを確認してください。

```kotlin
android {
    defaultConfig {
        minSdk = 23
    }
}
```

## API キーの設定

`MainActivity.java` には指定どおりプレースホルダーをハードコードしています。

```java
private static final String API_KEY = "Your_API_Key";
```

実行時は `"Your_API_Key"` を取得済みの API key に置き換えます。実キーを含むファイルを GitHub や共有用 ZIP に含めるとキーが漏えいするため、この形式は手元でのプロトタイプ確認にのみ使用してください。

## 使用方法

1. Google AI Studio で Nano Banana 2 を使用可能な paid API key を取得します。
2. `MainActivity.java` の `"Your_API_Key"` を実 API key に置き換え、アプリを起動します。
3. `写真を選択` から写真フォルダ内の画像を選ぶか、`写真を撮影` で新しい写真を撮ります。
4. 画風を選択し、`画風を変換` をタップします。
5. API から返った変換済み画像が画面下部に表示されます。
6. `結果を保存` をタップすると、表示された生成画像を端末の `Pictures/Artify` に JPEG として保存します。

配布アプリにする場合は、API キーをクライアントへ埋め込まない認証・プロキシ設計へ移行してください。

## 画風プリセット

次の 10 種類から選択できます。

- 水彩画、浮世絵、油彩画、アニメ背景美術
- ゴッホ風、モネ風、ピカソ風（キュビスム）
- クリムト風、フェルメール風、ムンク風

実在の画家プリセットは表示名として選べますが、API へ送る指示は特定作品や画家名の複製指定ではなく、筆致、光、構図、色彩などの特徴をプロンプト化しています。

## API 実装

- Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image-preview:generateContent`
- Header: `x-goog-api-key`
- Input: style instruction text + JPEG の base64 `inline_data`
- Output: response parts に含まれる base64 `inlineData` 画像
- Transport: `HttpsURLConnection` の TLS 通信のみ。cleartext HTTP は manifest で無効化。

待ち時間と通信量を抑えるため、API へ送る入力画像を最大辺 1024 px の JPEG に縮小します。同じ写真で別の画風を試す場合は、作成済みの入力 JPEG を再利用し、端末側の縮小・圧縮処理を繰り返しません。

Nano Banana 2 のドキュメントは出力サイズ指定を案内していますが、実行時に `generationConfig.responseFormat.image.imageSize` を含む画像編集 request が拒否されたため、この最小実装では出力サイズを指定せず API の既定出力を使用します。保存操作は画面に表示済みの生成画像を直接保存し、追加の API 呼び出しや再生成は行いません。

`写真を撮影` は `TakePicture` と `FileProvider` でアプリ cache 内の一時ファイルへ撮影し、変換入力として読み込んだ後に削除します。撮影した元画像は保存せず、API で生成された結果のみを保存します。

保存処理は `MediaStore` を使います。Android 10 (API 29) 以降では追加権限なしで `Pictures/Artify` に保存し、Android 6.0〜9 (API 23〜28) では保存ボタンを押した時に限り `WRITE_EXTERNAL_STORAGE` を要求します。manifest ではこの権限を `android:maxSdkVersion="28"` に制限しています。

API が画像を返さない場合、レスポンスの `finishReason` や `promptFeedback.blockReason` を読み取り、`IMAGE_SAFETY`、`NO_IMAGE`、`IMAGE_RECITATION` などの原因に合わせた再試行メッセージを表示します。

## References

- [Nano Banana image generation - Gemini API](https://ai.google.dev/gemini-api/docs/image-generation)
- [Build with Nano Banana 2 - Google Blog, February 26, 2026](https://blog.google/innovation-and-ai/technology/developers-tools/build-with-nano-banana-2/)
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [TakePicture contract](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture)
- [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
- [Access media files from shared storage](https://developer.android.com/training/data-storage/shared/media)
