# 进度与待办

## 已完成

- [x] 网页版全部功能（录入 / 复习 / 斩题 / 出卷 / 统计 / 标签口诀）
- [x] 公式渲染：KaTeX 本地化，支持 `$..$`、`$$..$$`、`\(..\)`、`\[..\]`、裸 LaTeX
- [x] 图片裁剪（自动框选 + 旋转 + 手动拖框）
- [x] 回收站 15 天自动清理
- [x] 部署到 Cloudflare Worker（静态资源 + /proxy 同源）
- [x] 版本自检（有新版本时页面底部提示刷新）
- [x] 代理自愈（本机 → 线上自动切换，带连通性测试）
- [x] 学习报告：本地即时诊断（0 token / 秒出）+ AI 细说改为可选
- [x] Android WebView 壳代码
- [x] 壳里的 alert / confirm 对话框（WebView 默认不实现，confirm 会静默返回 false）
- [x] **Android 构建成功**：debug APK 能出包、能校验签名（v1.2.0 / versionCode 3）
- [x] **原生 HTTP 桥**：Java 侧 `HttpURLConnection` + 异步回调，APP 里不再依赖代理
- [x] **去掉硬编码的 Worker 地址**：`REMOTE_PROXY` 默认空，用户要就自己填
- [x] **拍照能调起相机**（之前「拍照」按钮实际打开的是相册）
- [ ] 真机验证（相机 / 相册 / 返回键 / 识图全链路）
- [x] **Release 签名配置 + GitHub Actions**（CI 已写好并本地验证过签名路径，只差你把密钥填进 Secrets）
- [x] 开源前的隐私清理（Cloudflare 账号信息已移出仓库）
- [ ] README 截图 / 演示 GIF

## 下一步（按顺序）

1. 装 `dist/cuoti-debug-1.2.0.apk` 到手机，把 拍照 → 识图 → 入库 → 复习 走一遍
2. 按 `docs/发布清单.md` 走一遍：生成 keystore → 配 4 个 Secrets → 提交推送 → 打 tag
3. README 补截图 / GIF

## 这一版改了什么（v1.2.0 / APP_VER v28）

- **拍照**：`onShowFileChooser` 里先看 `params.isCaptureEnabled()`，
  为 true 就发 `MediaStore.ACTION_IMAGE_CAPTURE` + FileProvider（`cache/camera/`），
  拍完把 content URI 交回 WebView；失败自动退回相册
  （配套：manifest 的 `<queries>`/`<provider>`、`res/xml/file_paths.xml`、`androidx.core`）
- `MainActivity.java`：新增 `NativeHTTP` 桥（异步回调 `window.__nativeHTTPDone`）
- `web/index.html`：`aiChat()` 优先走原生通道；`REMOTE_PROXY` 置空；设置页在 APP 内
  把「代理」那一段换成「已启用原生通道」的说明
- `android/gradle.properties`：删掉 Linux 的 trustStore 路径（Windows 上会让 SSL 失败）
- 构建环境与中文路径的问题，见 `docs/本地构建.md`

## 已知限制

- `usesCleartextTraffic=true`：允许 http 接口，方便用户填自建/校园网关；开源前可以考虑收紧
- WebView 里 `checkNewVersion()` 会请求本地的 `version.json`，无害，留着
- 目前只有 debug 签名；正式分发必须换 release keystore
- 拍出来的照片存在 App 私有缓存 `cache/camera/`，系统空间紧张时可能被清理（照片本身已经
  压缩进 IndexedDB，不受影响）
