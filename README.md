# Moments Android

把 [kingwrcy/moments](https://github.com/kingwrcy/moments)（Docker 部署的「极简朋友圈」）包成一个安卓 APP，
解决「只能用浏览器输网址登录」的痛点：**第一次启动填一次服务器地址和端口，之后点开 APP 就是完整的 Web 端体验**。

- 支持 Android 9（API 28）及以上
- 原生 Kotlin + WebView（不是套壳 H5 打包，占用小、启动快）
- 跟浏览器一致的能力：localStorage 登录态、图片/视频上传、视频全屏、音乐外链、外链跳浏览器、
  长按照片保存、文件下载、自签名证书、HTTP 明文（局域网 http://ip:3000）

---

## 一、APP 能力说明

| 功能 | 说明 |
| --- | --- |
| 首次配置 | 首次启动弹出配置页：协议（HTTP/HTTPS）、主机或域名、端口（默认 3000）、可选子路径；点「测试连接并保存」会先真连一次服务器 |
| 等价浏览器 | 直接用 WebView 加载你填的地址，前端是 Nuxt SPA，所有路由/API 都走同源 `/api`，行为与浏览器完全一致 |
| 登录态 | `localStorage` + Cookie 持久化，退出 APP 再进来仍然在线 |
| 上传 | `<input type="file">` 全部走系统选择器：相册单选/多选、拍照、录像都能用（头像 / Memo 配图 / 视频） |
| 视频 | 支持 WebView 全屏播放（横竖屏自适应，退出 APP 时才释放） |
| 外链 | 站外链接（豆瓣卡片、友情链接、外部引用）自动交给系统浏览器；同站链接留在 APP 内 |
| 撤回 / 返回 | WebView 历史回退优先，回到首页后连按两次返回退出 |
| 下拉刷新 / 顶部进度条 | 有 |
| 证书 | HTTPS 自签名证书弹窗确认（可勾选「始终允许」），也可在配置页直接勾「忽略证书错误」 |
| 明暗主题 | APP 外壳跟随系统深色模式，网页本身的暗黑模式由 Moments 自己控制 |

### 原生客户端能力（APP 壳）

| 功能 | 说明 |
| --- | --- |
| 底部导航 | 首页 `/`、日历 `/user/calendar`、我的 `/user/{id}`，中间圆形按钮是「发布」 |
| 原生发布 | 悬浮按钮进入原生写朋友圈页：正文（Markdown 快捷插入）+ 选图/选视频 + 标签 + 公开/私密 + 置顶，走 `/api/file/upload` → `/api/memo/save`，与网页端 `MemoEdit.vue` 载荷一致 |
| 原生登录 | 直接调 `/api/user/login`，拿到 token 后写入 APP，同时同步进 WebView 的 `localStorage('global')`，网页端立即变成已登录 |
| 状态栏适配 | 沉浸式 + 按 `WindowInsets` 精确补齐顶栏/底栏/悬浮按钮，顶部文字不会再压到状态栏 |
| 顶栏 | 上滑时自动收起，下拉回来；右上角菜单可直达 标签 / 友链 / 设置 / 登录 / 退出 / 服务器配置 |

---

## 二、怎么编译出 APK

### 方式 A：GitHub Actions 云构建（不用装任何开发工具）

仓库里已经带好了构建脚本 `.github/workflows/build-apk.yml`，
push 到 GitHub 后云端自动打包，产物在 Actions 页面直接下载。

1. 在 GitHub 上新建一个**空仓库**（不要勾选 README / .gitignore）。
2. 在本目录执行（把地址换成你的仓库）：

```bash
cd moments-android
git init
git add .
git commit -m "Moments Android client"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

3. 打开仓库的 **Actions** 页，等 `Build Android APK` 跑完（约 3~6 分钟）。
   首次也可以手动触发：`Actions → Build Android APK → Run workflow`。
4. 点进这次运行，在 **Artifacts** 里下载 `moments-apk` 压缩包，里面有两个：
   - `app-debug.apk`：可以直接安装
   - `app-release.apk`：未配置签名时用 debug 签名兜底，同样可以直接安装
5. 传到手机安装即可（安装时允许「未知来源」/「允许来自此来源的应用」）。

> 想要正式签名：在仓库 `Settings → Secrets and variables → Actions` 加 4 个 secret：
> `KEYSTORE_BASE64`（keystore 文件 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，
> 下次构建出的 release 包就会用你的签名。

### 方式 B：Android Studio（本地开发用）

1. 安装 [Android Studio Ladybug 或更新版本](https://developer.android.com/studio)，SDK Manager 里勾选：
   - Android SDK Platform 35（compileSdk）
   - Android SDK Build-Tools 35.x
   - Android SDK Platform-Tools
2. `File → Open` 选中本目录（`moments-android`），等待 Gradle Sync 完成。
3. 连接手机（开启 USB 调试）后点 **Run** 直接装到手机；
   或者 `Build → Build Bundle(s) / APK(s) → Build APK(s)` 产出 APK。
4. 要签名发布包：`Build → Generate Signed Bundle / APK` → 选 APK → 建/选 keystore → release。

> 本目录只放了 `gradle/wrapper/gradle-wrapper.properties`，没有 `gradle-wrapper.jar`。
> Android Studio 会在同步时自动补齐 gradlew / jar；
> 如果你要在命令行编译且本机已有 Gradle（8.9 以上），在本目录执行一次 `gradle wrapper` 即可生成。

### 方式 C：命令行

前置：JDK 17、Android SDK command line tools（`$ANDROID_HOME` 已设置，或根目录建 `local.properties` 写 `sdk.dir=你的SDK路径`，注意 Windows 路径要双反斜杠或正斜杠）。

```bash
# 生成 wrapper（本机没 Gradle 的话，用 Android Studio 打开一次即可自动补上）
gradle wrapper --gradle-version 8.9

# 调试包
./gradlew assembleDebug     # Windows: gradlew.bat assembleDebug

# 发布包（需要签名配置，或用 Build → Generate Signed Bundle / APK）
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 发布版本体：为防止别人拿到你的 APK 改服务器地址，`MainActivity` 里
> `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` 只在调试版开启远程调试。

---

## 三、项目结构

```
app/src/main/java/com/moments/android/
├── MainActivity.kt              主页面：WebView 宿主、菜单、上传/下载/全屏/长按菜单
├── ConfigActivity.kt            配置页：协议/地址/端口/子路径、连通性测试、忽略证书
├── data/
│   ├── AppPrefs.kt              配置持久化（SharedPreferences）
│   └── ServerUrls.kt            地址拼装 + 同站判断
├── web/
│   ├── WebViewSetup.kt          WebView 能力开关（尽量对齐浏览器）
│   ├── MomentsWebViewClient.kt  链接路由、错误页、证书处理
│   ├── MomentsWebChromeClient.kt 进度/标题/文件选择/全屏/JS 弹窗
│   └── WebHost.kt               宿主页需要实现的回调
└── util/
    ├── HttpProbe.kt             配置页连通性探测（含信任自签名证书）
    └── ExternalOpener.kt        用系统浏览器打开链接
```

---

## 四、注意事项

1. **HTTP 明文**：Android 9 起默认禁止明文流量，本项目已在 `AndroidManifest` 打开
   `android:usesCleartextTraffic="true"`，并在 `res/xml/network_security_config.xml` 允许明文 + 用户安装的 CA 证书。
   如果你对安全更敏感，建议给服务器配 HTTPS 后删掉这两个配置。
2. **Google reCAPTCHA**：如果你在后台开了评论验证码，Google 对 WebView UA 有限制，
   本项目已把 UA 里的 `; wv` 标记去掉（见 `WebViewSetup.stripWebViewUserAgent()`）。
3. **数据备份**：Moments 本身会自动把数据库备份到 `$DB` 目录，与 APP 无关。
4. 服务器换地址/端口：APP 右上角菜单 → 「服务器配置」重新填即可，不需要清数据。
5. 隐私：APP 只申请网络权限；「保存图片/下载」在 Android 9 上才会额外申请存储权限。
