# Illusion 设计文档总览

本文是 `Illusion\docs\` 的入口。源码里的注释只保留"读代码时立刻要用到的约束"，
**用法、整条数据流、硬约束与已知缺陷**统一收在这里。

> 阅读顺序建议：先看下面的「文档地图」，再按你要改的子系统点进去。
> 每篇文档结尾都有「文件导航」表，用来在源码与文档之间来回跳。

## 这个项目是什么

Illusion（幻光影现）是 Minecraft NeoForge 模组，把渲染后端换成自研的 **Direct3D 12** 实现：

- JVM 侧是 Kotlin，实现了 Blaze3D 的 GPU 抽象（`GpuDevice` / `CommandEncoder` / `RenderPass` …）。
- native 侧是 C++23 动态库 **MCDR**（同一仓库的 `MCDR\` 目录），通过 **Java FFM API** 调用，
  **不使用 JNI**。
- 高级特性：DLSS / FSR / XeSS 超分，DLSS-G / FSR-FG / XeSS-FG 帧生成，NVIDIA Reflex，
  TAA / SMAA，mesh shader（meshlet），VRS 与渲染比例；光线追踪**尚未实现**。

## 文档地图

| 文档 | 主题 | 什么时候看 |
| --- | --- | --- |
| [`superresolution/README.md`](superresolution/README.md) | 超分总览：后端抽象、渲染比例、超分掩码 | 改升采样、加新超分后端 |
| [`superresolution/dlss.md`](superresolution/dlss.md) | DLSS / DLAA 专项：NGX、preset、输出纹理 | 动 DLSS 路径、排查 NGX 初始化 |
| [`framegen/README.md`](framegen/README.md) | 帧生成与 Reflex：hudless 捕获、UI 合成、限帧 | 改插帧、改呈现时序 |
| [`antialiasing/README.md`](antialiasing/README.md) | TAA / SMAA / 后期锐化：历史缓冲、速度缓冲 | 残影、抖动、GUI 拆分问题 |
| [`mesh&vrs/README.md`](mesh&vrs/README.md) | mesh shader（meshlet）与 VRS / 渲染比例 | 地形管线、性能取舍 |
| [`shaders/README.md`](shaders/README.md) | 着色器管线：原版管线 → HLSL → DXC → PSO | 编译失败、绑定错位 |
| [`misc/backend-and-device.md`](misc/backend-and-device.md) | D3D12 后端、设备、资源与内存管理 | 加资源类型、改每帧流程 |
| [`misc/ffm-abi.md`](misc/ffm-abi.md) | JVM ↔ native 的 FFM ABI | 新增或修改 native 导出 |
| [`misc/debug-and-crash.md`](misc/debug-and-crash.md) | 崩溃处理、诊断与日志体系 | 崩溃、黑屏、日志看不懂 |
| [`misc/mixins-and-earlywindow.md`](misc/mixins-and-earlywindow.md) | Mixin 注入点地图、早期窗口与 FML 兼容 | 找注入点、窗口创建失败 |
| [`misc/settingscreen/README.md`](misc/settingscreen/README.md) | 配置系统与设置界面 | 加开关、改界面 |

## 文档写作约定

- **语言**：简体中文；技术名词保留英文原文（DLSS / FSR / XeSS / VRS / SMAA / TAA / PSO / SRV /
  UAV / FFM / ABI …），不硬译。
- **面向人类读者**：先说"它是什么、怎么用"，再给架构与数据流，最后才给文件与函数级细节。
- **可查证**：结论必须来自代码。写具体数值、枚举名、配置键名，而不是"某个阈值"。
  代码里没写清的地方标注「代码中未明确」，不要替它补脑。
- **只写当下**：不写设计史、方案对比与"当初为什么这样"；必须遵守的写法写成祈使句，
  已经发现的缺陷单独列成警告清单。
- **源码导航**：每篇结尾的表格用相对 `Illusion\src\main\kotlin\dev\nts\expdxr\` 的路径。
- **文档维护**：改了对应子系统的行为，同一次改动里更新文档；文档与代码冲突时以代码为准，
  并顺手把文档改对。
- **注释与文档的分工**：源码注释只写"违反就会出错"的约束（一两行），解释性内容放这里，
  注释里需要展开时给出指向本文档的路径。

## 相关文档

- `MCDR\docs\` —— native 库 MCDR 的设计文档（C++ 侧，另行编写）。
- `Illusion\README.md` / `Illusion\README_ZH.md` —— 面向玩家的功能介绍与使用说明。
