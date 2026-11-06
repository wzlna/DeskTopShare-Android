# DeskTopShare Android 平板客户端

原生 Java 实现，不依赖 AndroidX、Kotlin 或 NDK，APK 约 39KB。协议与 Linux 客户端一致：TCP 8989 私有协议、JPEG 全屏帧、150x150 增量块、UDP 8990 主机发现。

## 安装

```bash
adb install -r DeskTopShare-Android-tablet.apk
```

或者把 APK 拷到平板，允许“安装未知来源应用”后点击安装。

- 包名：`com.yinrg.dts`
- 最低 Android 7.0（API 24），targetSdk 34
- 已在 MuMu Android 15 x86_64 上验证连接、画面、控制
- 纯 Java，无 native 库，arm64 / x86_64 平板都可运行

## 使用

1. 填写服务端 IP、端口（默认 8989）、用户名；查看密码按需填写。
2. 点“连接”。
3. 需要控制时点“申请控制”，在 Windows 服务端同意。
4. “搜索主机”通过 UDP 8990 扫描局域网；部分模拟器 NAT 收不到 UDP 广播，直接填 IP 即可。
5. 顶部“键盘”呼出输入法；“按键”显示 Esc/Tab/Ctrl/Alt/Enter/方向键等功能键条。

触控：

- 单击：左键
- 长按：右键
- 拖动：按住拖动
- 双击：双击
- 双指上下滑动：滚轮

## 限制

- 无声音、文件传输、Office 预览
- 软键盘只映射英文/ASCII；中文 IME 提交的汉字无法通过 VK 发送
- 无手势缩放/平移，画面按比例适配屏幕
- 协议无 TLS，跨公网请走 VPN/SSH 隧道

## 从源码构建

需要 JDK 17+ 和 Android SDK（platform 34 / build-tools 34）：

```bash
cd Android
# 设置 ANDROID_HOME 或在 local.properties 写入 sdk.dir=/path/to/Android/Sdk
chmod +x gradlew
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`

源码构建默认用 debug 签名，方便直接安装测试。要发布自己的签名版本，请创建 keystore 并在 `app/build.gradle` 的 `signingConfigs` 中配置。仓库不包含任何私有签名密钥。

## 调试

```bash
adb shell am start -n com.yinrg.dts/.MainActivity \
  --es host 192.168.1.10 --ei port 8989 --es user android
```

模拟器可通过 TCP 端口反向映射连接宿主机服务端：

```bash
adb reverse tcp:8989 tcp:8989
# 然后在 App 里连接 127.0.0.1:8989
```
