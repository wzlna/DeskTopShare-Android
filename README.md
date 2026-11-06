# DeskTopShare Android 平板客户端（非官方第三方）

> **本项目是第三方、非官方的互操作客户端，不是 Yinrg 官方软件，与 Yinrg 无关联。**
>
> “DeskTopShare / 桌面屏幕共享”指的是 Windows 端的原始软件；本仓库只提供 Android 客户端，
> **不能单独工作**，必须连接已经运行在 Windows 上的官方 DeskTopShare 服务端。
> 协议由独立逆向实现，仅用于连接你已获授权使用的服务端。

## 它是什么

- 一个 Android 平板客户端，用来连接 Windows 版 DeskTopShare 的 `8989` 端口
- 不是 RTSP/RTP，而是 Yinrg 自研的 TCP 私有协议
- 服务端仍然是官方 Windows 程序；本项目不包含、不替代服务端
- 原生 Java，不依赖 AndroidX、Kotlin 或 NDK，APK 约 39KB

## 安装

```bash
adb install -r DeskTopShare-Android-tablet.apk
```

或者把 APK 拷到平板，允许“安装未知来源应用”后点击安装。

- 包名：`com.yinrg.dts`
- 最低 Android 7.0（API 24），targetSdk 34
- 纯 Java，无 native 库，arm64 / x86_64 平板都可运行
- 预编译 APK 为 debug 签名；源码构建默认也是 debug 签名。要发布自己的签名版本，请配置自己的 keystore

## 使用

1. 填写 Windows 服务端的 IP、端口（默认 8989）、用户名；查看密码按需填写
2. 点“连接”
3. 需要控制时点“申请控制”，在 Windows 服务端同意
4. “搜索主机”通过 UDP 8990 扫描局域网；部分模拟器 NAT 收不到 UDP 广播，直接填 IP 即可
5. 顶部“键盘”呼出输入法；“按键”显示 Esc/Tab/Ctrl/Alt/Enter/方向键等功能键条

触控：

- 单击：左键
- 长按：右键
- 拖动：按住拖动
- 双击：双击
- 双指上下滑动：滚轮

## 限制

- 本仓库只有 Android 客户端，没有服务端；服务端仍是 Windows 程序
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

仓库不包含任何私有签名密钥。 

## 调试

```bash
adb shell am start -n com.yinrg.dts/.MainActivity \
  --es host 192.168.1.10 --ei port 8989 --es user android
```

模拟器可通过 TCP 反向映射连接宿主机服务端：

```bash
adb reverse tcp:8989 tcp:8989
# 然后在 App 里连接 127.0.0.1:8989
```

## License

MIT，见 [LICENSE](LICENSE)。第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
