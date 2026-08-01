# MIUI 互联剪贴板 Hook

针对 Redmi K80 Pro / HyperOS 2 国行版的实验性 LSPosed 模块。

目标是验证并绕过关闭系统优化后，小米互联服务无法完成后台剪贴板唤醒和注册的问题。模块不修改小米账号、可信设备数据库或剪贴板内容。

## 当前版本

`0.1.1-probe` 是诊断和最小化绕过版本：

- 在 `com.miui.mishare.connectivity`、`com.milink.service`、`com.xiaomi.mi_connect_service` 和 `com.xiaomi.mirror` 中，将读取 `persist.sys.miui_optimization` 的结果定向视为 `true`。
- 在 `android` 的候选剪贴板服务中，仅当调用方包名（第一个参数）属于上述包且检查结果为 `false` 时，放行唤醒/权限所有者检查。
- 不修改全局系统属性，不改变其他应用的系统优化状态。

## 安装

1. 构建并安装 APK。
2. 在 LSPosed 中启用模块。
3. 勾选作用域：Android System、互联互通服务、小米互联通信服务、小米镜像服务。
4. 重启手机。
5. 保持系统优化关闭，测试手机和电脑之间复制纯文本。

查看日志：

```powershell
adb logcat -v time | Select-String "MiuiClipboardHook"
```

## 回滚

在 LSPosed 中停用模块并重启即可。若出现系统服务异常，进入 LSPosed 禁用模块或从安全模式卸载，不要继续重复重启。
