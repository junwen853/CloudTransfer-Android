# iCloud 照片搬家（Android）

把 Android 手机里**还没在 iCloud 的**照片和视频，传到 iCloud 照片。已经在 iCloud 的不会重复上传。

不需要 iPhone，也不需要电脑。登录在 App 内嵌的 iCloud 网页里完成，App 拿不到你的 Apple ID 密码。

## 能做什么

- **按内容查重，不看文件名。** 每个文件都会算一遍 Apple 的内容指纹，和 iCloud 里每一个原始文件逐一比对，用的就是 iCloud 自己的指纹算法。改了名的文件也能认出来，同名但内容不同的文件也不会被误判成重复。
- **同一张照片换了文件也能认出来。** 照片被改名、去掉了位置信息，或者被重新保存过（比如通过聊天软件转发），只要拍摄时间还在，App 会再拿画面和 iCloud 里的缩略图比对一次。认出来的会单独列出，你可以逐张选择“仍要上传”。
- **上传的是原图，地点和相机信息都在。** App 会申请“访问照片位置信息”权限。没有这个权限，Android 会把照片里的 GPS 位置抹掉。上传的字节和手机里的文件一模一样，发送时会边传边校验。
- **传完会到 iCloud 里核对。** 每传一个，都要在 iCloud 里查到同一个内容指纹的原始文件，才算成功。如果中途断网、App 被杀，下次会接着确认，不会重复上传。
- **支持的格式：** JPEG、HEIC/HEIF、PNG、WebP、TIFF、DNG 等 RAW，MP4/MOV/M4V 视频，单个文件小于 500 MB。
  - **动态照片**（Motion Photo，视频嵌在图片文件里）当作照片上传。
  - **GIF、3GP/MKV/WebM 视频、500 MB 及以上的文件**，iCloud 网页上传不接收，App 会单独列出来并说明原因。
- **自动备份：** 可以设成每天夜里、只在 Wi‑Fi 和充电时自动上传新照片。
- **共享图库、隐藏相簿**里的照片也算“已在 iCloud”。

## 使用前提

- Android 10 或更新版本。
- 在 iPhone、iPad 或 Mac 上打开“**在网页上访问 iCloud 数据**”（设置 › Apple 账户 › iCloud）。
- 如果开启了**高级数据保护**，网页访问需要在受信任设备上批准。
- 支持全球 iCloud 和中国大陆 iCloud（云上贵州）。

## 安装

在 [Releases](../../releases) 页面下载最新的 `CloudTransfer-*.apk`，在手机上安装。

## 怎么用

1. 打开 App，点“授权”，选“全部允许”。
2. 点“登录 iCloud”，在网页里登录。等照片网格出现，App 会自动接管。
3. 点“检查”，看看哪些还不在 iCloud；或者直接点“检查并上传”。
4. 第一次检查要读取整个 iCloud 照片库的内容指纹，几万张大约需要几分钟。之后只读取新增的部分。

## 隐私

- 没有任何服务器。App 只和 Apple 的 iCloud 服务通信。
- 登录信息用 Android Keystore 加密保存在手机上，不会进入系统备份。
- App 不读取、不保存你的 Apple ID 密码。

## 限制

- App 用的是 iCloud 网页版的接口，Apple 改接口时可能需要更新 App。
- 网页上传不能生成苹果的 Live Photo；动态照片在 iCloud 里显示为静态照片（文件本身完整保留）。
- “同一张照片”的识别依赖拍摄时间。如果拍摄时间也被抹掉，又被重新压缩过，就只能当作新照片。
- 本项目与 Apple 无关。iCloud 是 Apple Inc. 的商标。

## 从源码构建

需要 JDK 17+ 和 Android SDK（compileSdk 37）。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

发布版签名需要在 `~/.gradle/gradle.properties` 里配置：

```properties
CT_RELEASE_STORE_FILE=/path/to/release.jks
CT_RELEASE_STORE_PASSWORD=...
CT_RELEASE_KEY_ALIAS=...
CT_RELEASE_KEY_PASSWORD=...
```

然后运行 `./gradlew :app:assembleRelease`。

## 许可

MIT
