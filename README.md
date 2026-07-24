# LocalPlay

Android 纯本地视频播放器（基于 PRD + Figma 原型）。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- Media3 ExoPlayer
- Room（播放进度）
- DataStore（设置）
- Coil（缩略图）

## 环境

- JDK 17（`C:\Program Files\Java\jdk-17`）
- Android SDK（`local.properties` 已配置）
- minSdk 26 / targetSdk 35

## 构建

```bat
gradlew.bat assembleDebug
```

Android Studio 打开本目录即可同步运行。

## 已实现（对齐原型）

- 权限申请 / 永久拒绝引导
- 本地视频扫描 + 目录分组列表
- 搜索、排序、续播角标 / 进度条
- 续播弹窗、删除确认、长按菜单
- 播放页（进度、倍速、旋转、锁屏手势提示）
- 视频详情、设置

## Figma 原型

https://www.figma.com/design/yau5bJ7xv9ixGMGJoCrcyT
