# Third-party notices

本仓库自有代码使用 MIT License。发布包和运行环境包含以下第三方组件：

## PySide6 / Qt

Linux 客户端依赖 PySide6，完整发布包会内置 PySide6/Qt 运行库。

- PySide6: LGPLv3 / commercial
- Qt: LGPLv3 / commercial
- 项目地址: https://www.qt.io/qt-for-python
- 许可证: https://www.gnu.org/licenses/lgpl-3.0.html

完整包中的 Qt 库以动态库形式提供，用户可替换为兼容的 Qt/PySide6 版本。完整包的其余部分为 MIT 代码。

## Android

Android APK 只使用 Android Framework API，不包含 AndroidX、Kotlin、NDK 或其他第三方运行时库，因此不额外引入第三方许可证。
