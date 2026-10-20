<div align="center">

<img src="assets/logo_flat_white.jpg">

<img src="https://img.shields.io/github/actions/workflow/status/NTS-Official/Illusion/build.yml?style=for-the-badge">
<img src="https://img.shields.io/badge/Powered%20by%20Kotlin-Java%20FFM%20API-orange?style=for-the-badge&logo=kotlin">

</div>

# Illusion
[English] | [[简体中文](README_ZH.md)]
> [!CAUTION]
> This is an experimental project that may not be introduced to real production environment ever.
## Introduction
> [!WARNING]
> This mod is still in early development; it is yet another of the [author](https://github.com/NTS-Official)'s [hole-digging projects that may never be finished]().

Illusion is a Minecraft neoforge mod based on Microsoft's DirectX 12 backend, aimed at bringing advanced graphics features to Minecraft:like ray tracing (not implemented), mesh shaders (not implemented), temporal anti-aliasing, deep learning super sampling, frame generation (not implemented), ray reconstruction and ray denoising (not implemented), and more.
It also takes advantage of DirectX's strengths on the Windows platform and Microsoft's PIX diagnostic tools to create an experimental platform that's easy to develop on.

## Details
[DirectXMod](https://github.com/366862732/DirectXmod) was implemented with JNI. Following the changes in Minecraft 26.1 (Java 21 upgraded to Java 25), this project replaces the whole JNI implementation with the [Java FFM API](https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html), which is theoretically faster and makes calling and implementing native code safer and easier.

# Acknowledgments
This is the acknowledgements part of the project DXRMC.

> [!NOTE]
> ### CC0
>
> Part code of this project was modified from [DirectXMod](https://github.com/366862732/DirectXmod), whose author is [xiaozi](https://github.com/366862732).

## References
[VulkanMod](https://github.com/xCollateral/VulkanMod)、[Radiance](https://github.com/Minecraft-Radiance/Radiance) & [MCVR](https://github.com/Minecraft-Radiance/MCVR)、[C2ME](https://github.com/RelativityMC/C2ME-fabric)、[Bye-Pregen](https://github.com/MoePus/Bye-Pregen) were referred as some extraodinary examples of implementation in this project. Tribute to these developers who contribute to community selflessly!

## External libraries
This project uses [Microsoft's DirectX 12 Ultimate technology](https://devblogs.microsoft.com/directx/announcing-directx-12-ultimate/). Please refer to [this page](https://learn.microsoft.com/zh-cn/windows/win32/direct3d12/what-is-directx-12-) for more information.<br>
This project uses [NVIDIA's DLSS (Deep Learning Super Sampling) technology](https://www.nvidia.com/en-us/geforce/technologies/dlss/). Please refer to [this page](https://github.com/NVIDIA/DLSS) for more information.<br>
This project uses [AMD's FSR technology](https://www.amd.com/zh-cn/products/graphics/technologies/fidelityfx/super-resolution.html). Please refer to [this page](https://gpuopen.com/amd-fsr-sdk/) for more information.<br>
This project uses [Intel's XeSS technology](https://www.intel.cn/content/www/cn/zh/products/docs/discrete-gpus/arc/software/gaming-technologies.html). Please refer to [this page](https://github.com/intel/xess) for more information.<br>
Only the Intel XeSS runtime ships inside the mod JAR. The NVIDIA DLSS and AMD FidelityFX runtimes are not redistributed: a released build reports those SDKs as unavailable unless you place the vendor DLLs next to `MCDR.dll` yourself, and development builds stage them into `run/dxrmc/` via `gradlew copyVendorDlls`.<br>
Special thanks to all contributors of open-source libraries used in this project. If any are not credited and should be, please inform the author and credit will be applied where required.

# Disclaimer
This is a community-made third-party mod and is not affiliated with, authorized, sponsored, endorsed by, or otherwise officially connected to Mojang Studios or Microsoft. A commonly used wording on mod platforms is: "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."<br>
Minecraft and related names, logos, and assets are trademarks and/or intellectual property of Mojang Studios and/or Microsoft.<br>
This project is also not affiliated with, sponsored by, or endorsed by NVIDIA. NVIDIA / GeForce / RTX / DLSS are trademarks of NVIDIA Corporation and remain the property of their respective owners.<br>
This mod is provided "AS IS" without warranties. You assume all risks arising from its use (including, but not limited to, crashes, visual issues, data loss, or incompatibilities with other mods). Please back up your worlds and configs before installation.
