# MIUI 互联剪贴板 Hook

针对 Redmi K80 Pro / HyperOS 2 国行版的实验性 LSPosed 模块。

目标是在关闭系统优化后，定向修复小米互联剪贴板的后台唤醒检查。模块不修改小米账号、可信设备数据库或剪贴板内容，也不伪装 `persist.sys.miui_optimization`。

## 当前版本

`0.2.0-scope` 是收紧范围后的版本：

- 只在 `android`（system_server）作用域运行。
- 只尝试精确方法 `com.android.server.clipboard.ClipboardServiceStubImpl#checkProviderWakePathForClipboard(String, int, ProviderInfo, int)`。
- 只接受 `boolean` 返回值、四个精确参数类型且非静态的方法；找不到该签名时只记录日志，不扫描其他方法。
- 只有调用方属于小米互联白名单，且 Provider 身份属于已验证的短语输入/互联 Provider 时，才把 `false` 改为 `true`。
- 不再 Hook `SystemProperties`，因此不会影响互联进程读取 `persist.sys.miui_optimization` 的结果。
- 成功放行日志只记录调用方包名、UID、Provider 名称和方法名；详细反射签名由源码中的 `DEBUG` 开关控制，正式版默认关闭。

## 安装

1. 构建并安装 APK。
2. 在 LSPosed 中启用模块，只勾选 `Android System`。
3. 重启手机。
4. 保持系统优化关闭，测试手机和电脑之间复制纯文本。

查看日志：

```powershell
adb logcat -v time | Select-String "MiuiClipboardHook"
```

## 回滚

在 LSPosed 中停用模块并重启即可。若出现系统服务异常，进入 LSPosed 禁用模块或从安全模式卸载，不要继续重复重启。
