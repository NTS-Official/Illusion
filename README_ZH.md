# 幻光影现（Illusion）
[[English](README.md)] | [简体中文]

> [!CAUTION]
> 这是一个实验性项目，可能永远不会被引入真正的生产环境。

## 介绍
> [!WARNING]
> 该模组目前还处于早期开发中，属于[作者](https://github.com/NTS-Official)的又一个[挖坑不填的项目]()。

Illusion（幻光影现）是一个基于微软 DirectX 12 后端的 Minecraft NeoForge 模组，目标是把高级图形特性带给 Minecraft：例如光线追踪（未实现）、Mesh Shader（未实现）、时间性抗锯齿、深度学习超采样（DLSS）、帧生成（未实现）、光线重建与光线降噪（未实现）等等。
它还借助 DirectX 在 Windows 平台上的优势，以及微软的 PIX 诊断工具，打造一个便于开发的实验平台。

## 细节
[DirectXMod](https://github.com/366862732/DirectXmod) 使用的是 JNI 实现，本项目根据 Minecraft 26.1 的变化（即从 Java 21 升级到了 Java 25），将 JNI 实现全部替换为理论性能更好，且调用与实现更加便捷安全的 [Java FFM API](https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html) 实现。

# 鸣谢
这是 DXRMC 项目的鸣谢部分：

> [!NOTE]
> ### CC0
>
> 本项目的部分代码修改自 [DirectXMod](https://github.com/366862732/DirectXmod)，作者为 [爱睡觉的白洲梓](https://github.com/366862732)。本项目在其基础上做了现代化改造，并继续搭建更进一步的工程。

## 参考项目
[VulkanMod](https://github.com/xCollateral/VulkanMod)、[Radiance](https://github.com/Minecraft-Radiance/Radiance) 与 [MCVR](https://github.com/Minecraft-Radiance/MCVR)、[C2ME](https://github.com/RelativityMC/C2ME-fabric)、[Bye-Pregen](https://github.com/MoePus/Bye-Pregen) 在实现过程中被作为优秀的范例参考。向这些为社区无私奉献的开发者们致敬！

## 外部库
本项目使用了[微软 DirectX 12 Ultimate 技术](https://devblogs.microsoft.com/directx/announcing-directx-12-ultimate/)。更多信息请参考[这个页面](https://learn.microsoft.com/zh-cn/windows/win32/direct3d12/what-is-directx-12-)。

本项目使用了 [NVIDIA DLSS（深度学习超采样）技术](https://www.nvidia.com/en-us/geforce/technologies/dlss/)。更多信息请参考[这个页面](https://github.com/NVIDIA/DLSS)。

本项目使用了 [AMD FSR 技术](https://www.amd.com/zh-cn/products/graphics/technologies/fidelityfx/super-resolution.html)。更多信息请参考[这个页面](https://gpuopen.com/amd-fsr-sdk/)。

本项目使用了 [Intel XeSS 技术](https://www.intel.cn/content/www/cn/zh/products/docs/discrete-gpus/arc/software/gaming-technologies.html)。更多信息请参考[这个页面](https://github.com/intel/xess)。

特别感谢本项目所用开源库的所有贡献者。若有遗漏的署名，请联系作者，我们会按要求补上致谢。

# 免责声明
这是一个由社区制作的第三方模组，与 Mojang Studios 或 Microsoft 没有任何隶属、授权、赞助、认可或其他官方关联。模组平台上常用的表述是："NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."

Minecraft 及相关名称、徽标与素材是 Mojang Studios 和/或 Microsoft 的商标和/或知识产权。

本项目同样与 NVIDIA 没有隶属、赞助或认可关系。NVIDIA / GeForce / RTX / DLSS 是 NVIDIA Corporation 的商标，其所有权归各自所有者。

本模组按“原样”（AS IS）提供，不附带任何担保。使用本模组所产生的一切风险（包括但不限于崩溃、画面异常、数据丢失、与其他模组不兼容）由使用者自行承担。安装前请备份你的世界与配置。
