# VolumeAutoControl

摘下耳机后自动静音，并拦截随后的播放尝试，避免声音突然从扬声器外放。

## 功能

- 检测有线、蓝牙、USB 耳机的接入与断开
- 耳机断开时把媒体音量降到 0，铃声和闹钟不受影响
- 断开期间有应用尝试播放，立刻暂停并发通知说明原因
- 拦截次数达到设定上限后本轮放行，次数可配置
- 手动调整过音量即视为知情，本轮不再拦截
- 可限定生效时间段，支持跨午夜的区间
- 前台服务常驻，从最近任务划掉应用后守护依然有效

时段结束和耳机重新接入时都不会自动恢复音量，避免在无人操作时突然出声。耳机的音量由系统按设备独立记录，接回耳机会沿用耳机自己的音量。

## 环境

- Android Studio，JDK 11
- minSdk 24，targetSdk 36
- Kotlin + Jetpack Compose

## 构建与安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

小米、红米等机型需要先在开发者选项里打开「USB 安装」，否则安装会报 `INSTALL_FAILED_USER_RESTRICTED`。

## 首次运行需要授权

1. 通知使用权：用来读取媒体会话并暂停播放，应用内有跳转入口
2. 通知权限：Android 13 及以上需要，用于提示拦截原因
3. 电池优化白名单：建议加入，否则后台可能被系统回收

## 图标

图标由脚本生成，改完颜色或形状后重新运行即可覆盖自适应图标和各密度位图：

```bash
python3 tools/generate_icons.py
```

## 许可证

[MIT](LICENSE)
