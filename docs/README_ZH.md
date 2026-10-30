<div align="center">

<img src="../assets/logo_flat_white.jpg">
<br>
<img src="https://img.shields.io/github/actions/workflow/status/NTS-Official/Illusion/build.yml?style=for-the-badge">
<br>
<img src="https://img.shields.io/badge/Kotlin%202.5-Java%20FFM%20API-orange?style=for-the-badge&logo=kotlin">
<img src="https://img.shields.io/badge/DirectX%2012-grey?style=for-the-badge&logo=cplusplus&label=C%2B%2B23&labelColor=blue">
<br>
<img src="https://img.shields.io/badge/Streamline%202.14-green?style=for-the-badge&logo=nvidia&labelColor=grey">
<img src="https://img.shields.io/badge/FSR%20Redstone%202.3-grey?style=for-the-badge&logo=amd&labelColor=red">
<img src="https://img.shields.io/badge/XeSS%203-grey?style=for-the-badge&logo=intel&labelColor=blue">
<br>
<img src="https://img.shields.io/badge/NRC,%20NRD%20&%20SharC-green?style=for-the-badge&logo=nvidia&labelColor=grey">

</div>

# DirectX Minecraft 图形实验项目

[[English](../README.md)] | [[简体中文](README_ZH.md)]

> [!CAUTION]
> 这是一个实验性项目，可能永远不会被引入真正的生产环境。
> 
> 坦白地说，这个项目所做的基本上就是在“盲目追新”，在能跑起来的前提下引入尽可能多的新特性。

## 幻光影现

> [!Note]
> 着色器编译链正在经历重写，混合光追方案仍在非常不成熟的早期当中。

### 缘起

一开始，作者基本上就是想做一个 Radiance 类似物，只不过为是 Windows 平台特化的而已。<br>
在意识到`wgpu`的无能之后，遂放弃给 [Neolectrum](https://github.com/NTS-Official/wgpu-mc-neo) 立下更多 flag。<br>
接着作者发现了 [DirectXMod](https://github.com/366862732/DirectXmod)，鬼脑发力想出来几个神秘的点子，准备干点什么事情。<br>
鉴于原项目是比较难绷的许愿式编程，连 mc 自己的源码都放进仓库里，因此本项目也接续着采取 **vibe coding** 的方式进行大部分开发。真人只负责规划架构、不存在的审核以及压力 AI 修 bug（并非）。

### 介绍

> [!WARNING]
> 该模组目前还处于早期开发中，属于[作者](https://github.com/NTS-Official)的又一个[挖坑不填的项目](https://github.com/NTS-Official/wgpu-mc-neo)。

Illusion（幻光影现） 是一个基于微软 DirectX 12 后端的 Minecraft NeoForge 模组，目标是把高级图形特性带给 Minecraft：例如光线追踪（未实现）、网格着色器、时间性抗锯齿、NVIDIA 的 Streamline 全家桶等等。<br>
它还借助 DirectX 在 Windows 平台上的优势，以及微软的 PIX 诊断工具，打造一个便于开发的实验平台。<!-- 我在胡说什么？他妈的谁愿意来用这个几把 dx12 -->

## MCDR

### 简介
因为学（chao）的是 Radiance，所以项目组织方式也抄了一手，动态链接库单独放个仓库里，名字就叫 **[MCDR](https://github.com/NTS-Official/MCDR)**，其实是比较草率的一手决定。

## 设计文档

设计文档都在 [`docs/overview.md`](overview.md)，从总览开始读——它把每个子系统对应的文档列成了一张表。<br>
源码注释只保留"读代码时立刻会踩坑"的那几条约束；设计意图、数据流和架构取舍都写在文档里。

## 一些细枝末节

- 自 Minecraft release-26.1 起，游戏取消了反混淆，并将 Java 21 升级到了 Java 25 LTS，至此 FFM API 在退出预览特性后引来了第一个 LTS 版本。
- 自 release-26.3 起，游戏从 LWJGL 更换到了 SDL，由此更加容易对后端进行呈现的包装（26.4-snapshot-5 就引入了原生的 DXGI 包装呈现形式），因此本模组将放弃 26.2，直接把目标 Minecraft 版本提升到 release-26.3 进行下一步的开发。

# 鸣谢
这是 DXRMC 项目的鸣谢部分：

> [!NOTE]
> ## CC0
>
> 本项目的部分代码修改自 [DirectXMod](https://github.com/366862732/DirectXmod)，作者为[爱睡觉的白洲梓](https://github.com/366862732)。

## 参考项目
[VulkanMod](https://github.com/xCollateral/VulkanMod)、[Radiance](https://github.com/Minecraft-Radiance/Radiance) 与 [MCVR](https://github.com/Minecraft-Radiance/MCVR)、[C2ME](https://github.com/RelativityMC/C2ME-fabric)、[Bye-Pregen](https://github.com/MoePus/Bye-Pregen) 在实现过程中被作为优秀的范例参考。向这些为社区无私奉献的开发者们致敬！

## 外部库
- 本项目使用了[微软 DirectX 12 Ultimate 技术](https://devblogs.microsoft.com/directx/announcing-directx-12-ultimate/)。更多信息请参考[这个页面](https://learn.microsoft.com/zh-cn/windows/win32/direct3d12/what-is-directx-12-)。
- 本项目使用了 [NVIDIA DLSS（深度学习超采样）技术](https://www.nvidia.com/en-us/geforce/technologies/dlss/)。更多信息请参考[这个页面](https://github.com/NVIDIA/DLSS)。
- 本项目使用了 [AMD FSR 技术](https://www.amd.com/zh-cn/products/graphics/technologies/fidelityfx/super-resolution.html)。更多信息请参考[这个页面](https://gpuopen.com/amd-fsr-sdk/)。
- 本项目使用了 [Intel XeSS 技术](https://www.intel.cn/content/www/cn/zh/products/docs/discrete-gpus/arc/software/gaming-technologies.html)。更多信息请参考[这个页面](https://github.com/intel/xess)。

模组 JAR 内只附带 Intel XeSS 运行库。NVIDIA DLSS 与 AMD FidelityFX 的运行库不随发行包分发，请注意自行获取动态运行库。

# 免责声明
这是一个由社区制作的第三方模组，与 Mojang Studios 或 Microsoft 没有任何隶属、授权、赞助、认可或其他官方关联。模组平台上常用的表述是："NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."

Minecraft 及相关名称、徽标与素材是 Mojang Studios 和/或 Microsoft 的商标和/或知识产权。

本项目同样与 NVIDIA 没有隶属、赞助或认可关系。NVIDIA / GeForce / RTX / DLSS 是 NVIDIA Corporation 的商标，其所有权归各自所有者。

本模组按“原样”（AS IS）提供，不附带任何担保。使用本模组所产生的一切风险（包括但不限于崩溃、画面异常、数据丢失、与其他模组不兼容）由使用者自行承担。安装前请备份你的世界与配置。
