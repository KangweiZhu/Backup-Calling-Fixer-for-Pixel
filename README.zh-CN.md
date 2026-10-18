# Backup Calling Fixer for Pixel

[![Build](https://github.com/KangweiZhu/Backup-Calling-Fixer-for-Pixel/actions/workflows/build.yml/badge.svg)](https://github.com/KangweiZhu/Backup-Calling-Fixer-for-Pixel/actions/workflows/build.yml)

[English](README.md) | 简体中文

让你的 Pixel 用一张卡的流量，给另一张卡拉起 Wi-Fi 通话。

## 解决什么问题

双卡手机，常见的情况是这样的：

- **SIM1** 有流量套餐。
- **SIM2** 没有流量套餐，或者没开漫游。

在绝大多数手机上，只要 SIM1 开着移动数据，即使 SIM2 没漫游、手机也没连 Wi-Fi，手机都能借 SIM1 的流量，把 SIM2 的 Wi-Fi 通话拉起来，SIM2 照常打电话、接电话、收短信。

**Pixel 做不到，或者说很不稳定。** 这主要是因为 Pixel 的系统和 AOSP 源码之间有出入。Pixel 只有连着 Wi-Fi 时才能拉起 SIM2 的 Wi-Fi 通话；一旦断开 Wi-Fi，SIM2 的 Wi-Fi 通话就会失效，显示“仅限紧急呼叫”或“无服务”，既不能打电话、接电话，也收不到短信。

这是一个存在已久的问题：

- [Reddit：Has anyone gotten WiFi calling over cellular data?](https://www.reddit.com/r/GooglePixel/comments/1552ros/has_anyone_gotten_wifi_calling_over_cellular_data/)
- [pixel-volte-patch：Backup Calling Guide](https://github.com/kyujin-cho/pixel-volte-patch/discussions/272)

本项目从根源上解决这个问题，让你的 Pixel 在 Google 修好之前，也能正常用移动数据拉起 Wi-Fi 通话。

另外，用 **VPN by Google** 时，Pixel 本来是不支持这样做的（问题出在 Pixel 对系统 uid 的处理上）。本项目也提供了办法，让你开着 VPN by Google，照样能用 5G 流量拉起 Wi-Fi 通话。

## 它做了什么

打开 app，你能一眼看到 SIM2 的 Wi-Fi 通话现在是否正常。如果不正常，它会告诉你卡在哪一步，点 **一键修复** 就能解决：

1. **打开被藏起来的“备用通话”。** 新版 Pixel 的设置里已经没有“备用通话（Backup calling）”这个开关了，它被绑在了 SIM2 的“自动切换移动数据”上。这个选项一关，备用通话就跟着失效，界面上没有任何提示。
2. **让 Wi-Fi 通话组件不走 VPN。** Pixel 负责 Wi-Fi 通话的系统组件（iwlan）会被 VPN by Google 一起接管，接管后就拉不起 Wi-Fi 通话了。而 VPN by Google 的“排除所选应用”里根本看不到这个组件，没法手动排除。
3. **重新注册。** 改完设置后，Wi-Fi 通话有时不会自己恢复。app 会帮你重新注册一次，不用重启 VPN。

## 使用方法

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/)。本项目不需要 root，但需要 Shizuku 来修改系统设置。
2. 安装本 app（见下方“下载与编译”）。
3. 打开 app，允许 Shizuku 授权。
4. 顶部横幅是红色的，就点 **一键修复**。变成绿色，显示“<SIM2 名字> Wi-Fi 通话正常 · 用的是 <SIM1 名字> 的流量（跨卡）”，就好了。

以后如果又不行了，比如换了卡、重新启用 eSIM、改了 VPN 设置，打开 app 再点一次 **一键修复**，或者点 **重新注册**。

## 使用条件

- 两张卡同时启用的 Pixel（实体卡 + eSIM，或两张 eSIM）。目前在 Android 17 的 Pixel 9 Pro 上测试过。
- SIM2 的运营商配置要允许跨卡通话。app 里“运营商允许跨卡通话”一项会显示。大部分支持 Wi-Fi 通话的美国运营商（比如 T-Mobile 及其虚拟运营商）是允许的；不允许的话，需要先用 [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch) 这类工具打开。

## 需要知道的事

- **隐私：** 让 Wi-Fi 通话组件不走 VPN 之后，同一身份（uid 1000）下的其他系统进程也不走 VPN 了。它们的流量很少，主要是校时、下载 GPS 辅助数据这类系统杂事；你装的 app 照样走 VPN。通话内容本身在加密隧道里，本地运营商看不到。
- **“自动切换移动数据”：** 这个选项开着时，如果 SIM2 信号更好，系统可能把流量切过去。SIM2 没信号或者没开数据漫游的话，不会切。
- **在国外使用：** Wi-Fi 通话的运营商能从连接来源知道你不在本国。像 Tello、T-Mobile 这类运营商官方支持在国外用 Wi-Fi 通话，不影响使用。紧急情况请拨当地的急救电话。

## 下载与编译

在 [Releases](https://github.com/KangweiZhu/Backup-Calling-Fixer-for-Pixel/releases/latest) 下载最新的 APK。每个版本都用同一个密钥签名，新版本可以直接覆盖安装。

每次推送到 `main` 的测试版在 [Actions](https://github.com/KangweiZhu/Backup-Calling-Fixer-for-Pixel/actions/workflows/build.yml) 的 Artifacts 里（需要登录 GitHub）。测试版签名不同，装之前要先卸载正式版。

也可以自己编译：

```sh
git clone https://github.com/KangweiZhu/Backup-Calling-Fixer-for-Pixel.git
cd Backup-Calling-Fixer-for-Pixel
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 以上和 Android SDK（platform 36）。

<details>
<summary>技术细节（给想了解原理的人）</summary>

**为什么 Pixel 拉不起来：**

1. **备用通话开关被绑定。** 新版设置程序（`CrossSimCallingViewModel`）会把 `cross_sim_calling_enabled` 设成和非数据卡的 `MOBILE_DATA_POLICY_AUTO_DATA_SWITCH` 一致。“自动切换移动数据”关着，它就一直写 0。改运营商配置的工具只改 `carrier_cross_sim_ims_available_bool`（运营商是否允许），改不到这个用户开关。
2. **VPN 接管 iwlan。** `com.google.android.iwlan` 以 uid 1000 运行。VPN 覆盖 uid 1000 时，Pixel 版 iwlan 的网络服务会跟着 VPN 网络走，从 VPN 的网络信息里读不出数据卡，于是认为“数据不在另一张卡上”，跨卡注册被跳过。AOSP 的 iwlan 用的是 `registerSystemDefaultNetworkCallback`，不受 VPN 影响，Pixel 的版本和它不一样。
3. **排除后不自动恢复。** VPN 不断开时把 uid 1000 排除掉，iwlan 的网络监听仍然停在 VPN 网络上，直到备用通话关掉再打开（会让 iwlan 重新注册监听）或者 VPN 重连。

**app 怎么做的：**

通过 Shizuku 启动一个以 shell 身份运行的服务（shell 有 `MODIFY_PHONE_STATE` 权限），调用系统隐藏接口：

- `ITelephony.setMobileDataPolicyEnabled` / `setCrossSimCallingEnabled`：打开“自动切换移动数据”和备用通话；
- `ITelephony.getImsRegTechnologyForMmTel`、`dumpsys`：读取 IMS 注册方式和 iwlan / ePDG 状态；
- `IVpnManager.getAppExclusionList` / `setAppExclusionList`：在 VPN by Google 的排除名单里追加 iwlan，其他条目不动。

“写入运营商应用名单”是高级选项：给设置程序发 VPN by Google 平时用来上报运营商应用的广播（`UPDATE_CARRIER_APP_LIST`），会**整份替换**原来的名单。

</details>

## 免责声明

本项目与 Google 无关，也未获 Google 认可。Pixel 是 Google LLC 的商标。app 用到的系统隐藏接口可能在任何一次系统更新里变化，使用风险自负。

## 许可证

[MIT](LICENSE)
