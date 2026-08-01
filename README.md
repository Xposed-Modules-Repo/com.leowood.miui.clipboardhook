# MIUI 互联剪贴板 Hook

针对 Redmi K80 Pro / HyperOS 2 国行版的实验性 LSPosed 模块。

目标是在关闭系统优化后，定向修复小米互联剪贴板的初始化和后台唤醒检查。模块不修改小米账号、可信设备数据库或剪贴板内容。

## 当前版本

`0.2.2-provider` 是对照测试后的收紧版本：

- 在 `android`（system_server）作用域中，只尝试精确方法 `com.android.server.clipboard.ClipboardServiceStubImpl#checkProviderWakePathForClipboard(String, int, ProviderInfo, int)`。
- 在 `com.miui.mishare.connectivity`、`com.milink.service`、`com.xiaomi.mi_connect_service` 和 `com.xiaomi.mirror` 四个互联进程作用域中，定向 Hook `SystemProperties`。
- 只接受 `boolean` 返回值、四个精确参数类型且非静态的方法；找不到该签名时只记录日志，不扫描其他方法。
- 只有调用方属于小米互联白名单，且 Provider 名称/authority/className 精确匹配已验证的短语输入或互联 Provider 时，才把 `false` 改为 `true`；不按 Provider 的包名整体放行。
- 仅在四个小米互联进程中 Hook `SystemProperties` 的读取，将 `persist.sys.miui_optimization` 定向视为 `true`；system_server 不受该属性 Hook 影响。
- 属性 Hook 的去重键不包含包名；在每个进程内，同一个 `SystemProperties` 方法只安装一次，避免共享进程重复安装。
- 成功放行日志只记录调用方包名、UID、Provider 名称和方法名；详细反射签名由源码中的 `DEBUG` 开关控制，正式版默认关闭。

## 安装

1. 构建并安装 APK。
2. 在 LSPosed 中启用模块，并同时勾选以下五个作用域：
   - `Android System`（对应 `android`）
   - `com.miui.mishare.connectivity`
   - `com.milink.service`
   - `com.xiaomi.mi_connect_service`
   - `com.xiaomi.mirror`
3. 重启手机。
4. 保持系统优化关闭，测试手机和电脑之间复制纯文本。

查看日志：

```powershell
adb logcat -v time | Select-String "MiuiClipboardHook"
```

## 回滚

在 LSPosed 中停用模块并重启即可。若出现系统服务异常，进入 LSPosed 禁用模块或从安全模式卸载，不要继续重复重启。
