# My Record · Android 17 离线账本

使用 Kotlin、Jetpack Compose 和 Room。APK 在本机处理账本、附件及备份，最低系统为 Android 17（API 37）。

## 安装与打开

从本地安装签名试用包 `build/acceptance/trial/MyRecord-0.2.0-trial.apk`。如果设备已有同应用 ID 的版本，Android 只允许同一签名的包覆盖安装。请先核对签名和现有账本备份。

开发时用支持 API 37 的 Android Studio 打开 `H:\Codex\Program`，安装 Android SDK Platform 37.0 并选择兼容 JDK。当前工作区的命令行验证使用新版 Android Studio 的 `H:\AStudio\jbr`。初次下载构建依赖需要网络，使用 APK 可以断网。

## 功能入口

| 页面 | 操作 |
|---|---|
| 首次使用 | 新建账本和初始资金账户，或选择 Windows 完整备份并核对迁入 |
| 首页 | 切换当前账本、打开月历、管理账本、点击圆形记账按钮 |
| 账单 | 年月跳转、月历与明细、按日下钻、筛选、收支与转账、附件和月度预算 |
| 统计分析 | 周／月／年、全部或单账本、收入／支出、上期对比、趋势、占比和排行 |
| 资产 | 家庭／个人、现金与账户流水、估值和贷款余额、借贷计划及投资 |
| 更多 | 账本与类目维护、Windows 导入、Android 加密备份及恢复、期初盘点历史 |

月历点选日期可打开当天账单，记一笔会带入选中日期。趋势图支持悬浮、聚焦和触屏点按，选择时段后可查看明细。编辑中断后可从首页继续草稿。

Windows 备份使用 `.db`。Android 完整备份使用 `.mrbackup`，须用独立口令恢复。替换现有账本前会展示影响范围，并建立本机恢复前快照。

## 构建与测试

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat :domain:test :data:testDebugUnitTest :app:testDebugUnitTest
.\gradlew.bat :data:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:lintDebug :app:assembleRelease
```

单个模拟器上的 Room 和 App 设备测试应依次执行。测试使用虚构账本。测试基础设施及 M0–M5 证据见 [开发与验收记录](docs/development-verification.md)。
