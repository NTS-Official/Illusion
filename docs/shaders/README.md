# 着色器管线：原版 GLSL → HLSL → DXIL

本文说明本后端如何把 Minecraft 自带的 GLSL 着色器变成能交给 `CreateGraphicsPipelineState` 的 DXIL 字节码，
以及沿途的绑定编号、缓存与失败处理。只描述**代码里确实存在**的行为；代码没写清的地方标注「代码中未明确」。

## 1. 为什么不能把原版 GLSL 直接喂给 D3D12

D3D12 不认 GLSL，只接受 DXIL；DXIL 只能由 DXC 产出（`D3DCompile` 到 shader model 5.1 为止，产出的是 DXBC）。
因此本站必须自己持有一条链：**GLSL →（shaderc / glslang）→ SPIR-V（含反射）→ 生成 HLSL（SPIRV-Cross）→ DXC → DXIL**。
直接把手上的 GLSL 喂给 D3D12 不可能——没有 DXIL 就没有 `CreateGraphicsPipelineState` 的输入。

经 SPIR-V 而非文本直译，是因为只有它能提供稳定的「反射 + 重编号」：原版 GLSL 的 binding 由 shaderc / SPIRV-Cross
分配、无法预知，必须拿到 SPIR-V 后按自己的规则改写（见 6.1 与 10.1）。

图形管线硬要求 SM 6.9、没有 SM 5.1 / DXBC 兜底（`graphicsShaderModelProfile()` 只返回 `"6_9"` 或 `""`）：
本机 `dxcompiler.dll` 要能编 6_9（`caps.dxcSupportsSm69()`，下限 DXC 1.9.2502），设备 `CheckFeatureSupport`
要报 `>= 6.9`——只有 Agility SDK 1.619 的 `D3D12Core.dll` 报 6.9，inbox core 最高 6.8。

## 2. 全链路总览

```
原版 RenderPipeline（26.3 API）
  ├─[1] 取源      ShaderSource.getShader / D3D12FallbackShaderSource.get  → GLSL 文本
  ├─[2] 预处理    injectShaderDefines + expandShaderIncludes              → 自包含 GLSL
  ├─[3] shaderc   GLSL → SPIR-V（entry "main"，Vulkan 1.2）               → ByteBuffer
  ├─[4] 反射      createFromSpirv                                        → 名字 + decoration 偏移
  ├─[5] 重绑定    rebind(inputNames, entries) 原地改写 decoration         → SPIR-V 副本
  ├─[6] 转 HLSL   toHlsl（HLSL 档位 51 + point 兼容开关）                  → HLSL 文本
  ├─[7] 补丁      Y 翻转 / lightmap 翻转 / 全屏 blit 翻转                  → 打补丁的 HLSL
  ├─[8] DXC       compileHlslToDxil（内存 + 磁盘缓存）                     → DXIL 字节
  └─[9] PSO       原生 createGraphicsPipeline                            → Dx12Pipeline*
```

| 步骤 | 输入 | 输出 | 实现 |
| --- | --- | --- | --- |
| 1 取源 | `Identifier` + `ShaderType` | GLSL 文本 | `D3D12Device.compileShader` |
| 2 预处理 | GLSL + `ShaderDefines` | 自包含 GLSL | `D3D12ShaderCompiler` 顶层函数 |
| 3 shaderc | 自包含 GLSL | SPIR-V `ByteBuffer` | `createIntermediary` |
| 4 反射 | SPIR-V | 名字 + decoration 字节偏移 | `createFromSpirv` |
| 5 重绑定 | 输入名 + 绑定条目 | 改写后的 SPIR-V 副本 | `rebind` + `cloneForCompile` |
| 6 转 HLSL | SPIR-V | HLSL 文本 | `toHlsl` |
| 7 补丁 | HLSL + 管线位置/flipY | 打补丁的 HLSL | `D3D12ShaderCompiler.compile` |
| 8 DXC | HLSL + entry + profile | DXIL 字节 | 原生 `compileHlslToDxil` |
| 9 PSO | DXIL + 状态 + 绑定 | `Dx12Pipeline*` | 原生 `createGraphicsPipeline` |

## 3. 输入侧：原版管线描述里用到的字段

`D3D12Device.compilePipeline(pipeline, shaderSource, flipY)` 读 `pipeline.shaders`（顶点/片元源 id，缺某个时
退回 `pipeline.location`）、`shaderDefines`（参与着色器缓存键，也变成 `#define`）、`bindGroupLayouts`
（经 `flattenUniforms` 展平，用于「uniform 是否存在」与「是不是 texel buffer」）、
`vertexFormatBindings: List<VertexFormat?>`（索引即 slot）、`colorTargetStates: List<ColorTargetState?>`
（`null` 槽表示该 RT 未使用）、`depthStencilState`（`null` 表示不做深度测试）、`primitiveTopology` / `polygonMode` / `isCull`。

26.3 后端入口只拿到 `BackendRenderPipeline.CreateInfo`（SPIR-V 模块 + 状态），拿不到 `RenderPipeline`，故原版每次
编译先经 `FrontendGpuDevice.compilePipeline(RenderPipeline, …)`，由 mixin 登记进 `D3D12PipelineBridge`，后端按
`CreateInfo.name()`（= `location.toString()`）查回；查不到时报错并返回 `Pending.NULL`。另外 26.3 把
`setUniform(String, …)` 解析成**管线 uniform 列表下标**再回调 `backend.setUniform(index, value)`，该下标是
`CreateInfo.uniforms` 的位置（按 SPIR-V 描述符出现顺序），**不等于** `flattenUniforms` 的顺序；故
`compilePipeline(CreateInfo)` 把 `info.uniforms().map { it.name() }` 存进管线对象、`setPipeline` 时翻回名字，
名单为空时才退回 layouts 展平顺序。

## 4. 取源与后备着色器

`shaderSource?.getShader(key.id, key.type) ?: D3D12FallbackShaderSource.get(key.id, key.type)`。
主源是原版 `ShaderManager` 缓存，有一个必然踩到的窗口期：`compilationCache` 在首次 `apply()` 之前是
`Configs.EMPTY`，`getShader` 恒返回 `null`。而 `LoadingOverlay.render` 无条件画 mojang_logo，且不在
`Minecraft.loadCriticalShaders()` 预热的 3 条（GUI / GUI_TEXTURED / TRACY_BLIT）内，于是它成为第一条走空源的管线。

原版 GL 后端的 `GlRenderPass.setPipeline` 不校验 `isValid()`，这个竞态在 GL 上只是「画不出来」；D3D12 沿用
`VulkanRenderPass.setPipeline` 的严格语义（抛异常），于是变成致命错误。因此有 `D3D12FallbackShaderSource`：
`GameRendererPreloadUiShaderMixin` 在 `preloadUiShader` HEAD 捕获当时的 `ResourceProvider`
（= `vanillaPackResources.asProvider()`，已能解析 `minecraft:core/position_tex_color`），首次捕获打一行 info；
之后主源返回 `null` 就回退到它，首次资源重载后主源恢复、回退不再触发。两条源都拿不到时打
`Couldn't find source for {} shader ({})` 并返回 `D3D12IntermediaryShaderModule.INVALID`（`name="invalid"`、
`spirv=null`；对它 `rebind` 抛 `IllegalStateException("Attempt to use invalid shader")`，但上层在
`compilePipeline` 已把它拦成无效管线）。

include 同样走两条源。`readShaderInclude` 优先**直接按原版路径** `<namespace>:shaders/include/<path>` 从
`ResourceManager` 读，读不到才退回 `ShaderSource.getInclude` 的 `CachedIncludeResult`——后者的
`includeResultPtr()` 只在自己的 include 回调期间有效，回调返回后 vanilla 会释放它，异步再读是一块已释放内存
（表现：include 文本尾部混进 U+FFFD → shaderc 报 "unexpected token"）。故回退路径丢弃含替换字符的结果。

## 5. 阶段一：GLSL 预处理

### 5.1 define 注入

26.3 没有 blaze3d 的 `GlslPreprocessor`，`injectShaderDefines(source, defines)` 自己拼 `#define`：插在
`#version` 行**之后**（`#version` 必须是第一条指令，写在前面 shaderc 直接 parse error）、无 `#version` 时前缀、
保证结果以换行结尾。全局 define 是 `.define("gl_VertexID", "gl_VertexIndex")`、
`.define("gl_InstanceID", "gl_InstanceIndex")` 与 `.define("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE")`。

前两条是 GLSL 别名。第三条是深度约定开关：D3D12 深度 0..1 且 26.3 用 reversed-Z（近=1、远=0），原版
`oit_common.glsl` 靠它决定是否把 `deviceDepth` 转成 0..1。缺它时 OIT 三个 stage 的 `deviceToLinearDepth`
被压成几乎常数（zn=0.05 / zf=1000 下超过一格的深度都落到 ≈+0.05），depth bin 与 transmittance 小波全塌，
表现为半透明发闷、层叠顺序错。整棵着色器树只有它用这个宏。

### 5.2 include 展开

```kotlin
GLSL_INCLUDE_PATTERN = Pattern.compile(
    "^[ \t]*#[ \t]*include[ \t]+<([^>\\r\\n]+)>[ \t]*\\r?$", Pattern.MULTILINE)
```

26.3 只有 `#include <…>` 一种形式（`#moj_import` 已不存在）；`MAX_INCLUDE_DEPTH = 16`（正常链约 4~6 层）、
`MAX_EXPANDED_CHARS = 8 * 1024 * 1024`，超限抛 `ShaderCompileException`；展开处包 `// >>> include <id>` /
`// <<< include <id>` 便于对照行号；解析不到的 include **原样保留**，让 shaderc 报 "unexpected include directive"
（错误行号仍指向真实源码）；不做条件编译感知（`#if 0` 里的也会展开，原版 include 自带 `#ifndef` 守卫，
重复包含幂等）。

### 5.3 shaderc 选项

```kotlin
shaderc_compile_options_set_target_env(shaderOptions, 0, 0x402000)  // Vulkan 1.2
shaderc_compile_options_set_auto_bind_uniforms(shaderOptions, true)
shaderc_compile_options_set_auto_map_locations(shaderOptions, true)
shaderc_compile_options_set_generate_debug_info(shaderOptions)
shaderc_compile_options_set_optimization_level(shaderOptions, 0)
```

target env 必须 Vulkan 1.2，否则原版较新的 GLSL 语法被拒；**优化级别 0**（后续 SPIRV-Cross 与 DXC 都会再优化，
这里留着保调试信息、也让失败时的 SPIR-V 更贴近源码）；auto_bind / auto_map 打开后原版不写 `layout(binding=…)`
也能反射，但**我们不依赖这些初始编号**（第 6 步全部重写）。阶段号：`ShaderType.FRAGMENT -> 1`
（`shaderc_fragment_shader`），其余 `-> 0`；entry 恒 `"main"`。

失败时先把**实际送入编译器的源码**落盘到 `<user.dir>/dxrmc/glsl_<safe>.<stage>.<threadId>.failed.glsl`，再抛
`ShaderCompileException("Couldn't parse GLSL: …")`。文件名必须同时带阶段与线程：顶点/片元并发编译，共用一个名字
会互相截断，让人误判成 include 越界。

## 6. 阶段二：中间表示（SPIR-V + 反射）

模块持有 SPIR-V `ByteBuffer` 与一份反射快照：`SpvUniformBuffer(name, bindingOffset)`、
`SpvSampler(name, bindingOffset, dimensions)`、`SpvVariable(name, locationOffset)`。`createFromSpirv` 用
`spvc_context_create` → `spvc_context_parse_spirv` → `spvc_context_create_compiler(context, 0, ir, 1, pointer)`
建一个 **GLSL 后端**（backend 0）编译器、**只用来反射**（HLSL 后端在 `toHlsl` 才建）。资源类型
`UNIFORM_BUFFER=1`、`SAMPLED_IMAGE=7`、`STAGE_OUTPUT=4`、`STAGE_INPUT=3`；decoration `BINDING=33`、
`LOCATION=30`，偏移由 `spvc_compiler_get_binary_offset_for_decoration` 取，取不到抛
`"Couldn't find byte offset for location decoration of …"`。sampler 维度由 `spvc_compiler_get_type_handle` +
`spvc_type_get_image_dimension` 取，语义 **1=2D、3=Cube、5=Buffer**。顶点**输出**的 location 在反射结束时
被打平成 0..n-1，作为片元输入的重绑定依据。

### 6.1 rebind：把编号改成我们自己的顺序

`rebind(inputVariables, entries)` 的两次改写都是**原地写回 SPIR-V 的 int buffer**：① 输入 location 按
`inputVariables` 顺序编号，同名变量（矩阵、占多个 location）共享起始 location，靠
`variableName != previousName` 判断——换名字才写新 location，同名的后续 location 连续递增；
② 资源 binding 直接取 `entries` 下标 `i`，即**条目在列表里的位置就是它的寄存器号**。

未被消耗的反射项是硬错误：`Shader expects input variables / uniform buffers / samplers which are not being
provided: …`。类型校验也在此：`SAMPLED_IMAGE` 只接受 dim 1 或 3（否则
`"Unsupported texture dimensions '<dim>' for sampler <name>"`），`TEXEL_BUFFER` 只接受 dim 5。

因为就地改写，**原始缓存模块必须不变**：`cloneForCompile()` 用 `memCalloc` + `memCopy` 复制 SPIR-V、返回共享
反射信息的新模块；`compile` 一定在克隆体上 rebind，否则同一着色器第二次绑到别的管线会互相污染。

### 6.2 绑定条目的构造顺序

`addToBindGroup(entries, shader, pipeline)` 对顶点、片元各调一次，顺序是**顶点 UBO → 顶点采样器 → 片元 UBO →
片元采样器**，同名去重（先到先得）。UBO 的名字必须在 `flattenUniforms(pipeline.bindGroupLayouts)` 里，否则
`"Unable to find shader defined uniform (<name>)"`。采样器则**看 `UniformType`、不看名字是否存在**：26.3 会为
每个纹理 uniform 都报一条 `COMBINED_IMAGE_SAMPLER` 描述，「名字出现」不再等于「texel buffer」；只有
`type() == UniformType.TEXEL_BUFFER` 才记 UTB，其余记采样纹理（按名字判会把 `Sampler0` 当 UTB、再报
`"must have type of SpvDimBuffer"`）。

产物 `D3D12CompiledShader(vertexHlsl, fragmentHlsl, entries, vertexShaderInputs, semanticNames)` 五个字段都参与
后续所有步骤；`entries` 的顺序既决定 SPIR-V binding 0..n-1，也决定原生根签名的 CBV `b{i}` / SRV `t{i}` /
sampler `s{i}`。

## 7. 阶段三：HLSL 文本生成

`toHlsl(isVertex)` 用 SPIRV-Cross 的 **HLSL 后端**（`SPVC_BACKEND_HLSL`，`create_compiler` 第 4 参 = 1 即
capture 模式），设 `SPVC_COMPILER_OPTION_HLSL_SHADER_MODEL = 51`——这只是 SPIRV-Cross 的「HLSL 语法档位」，
与最终 DXIL 的 SM 版本无关（DXIL 由 DXC 按 `6_9` 编）；并启用 `SPVC_COMPILER_OPTION_HLSL_POINT_SIZE_COMPAT = 1`
与 `…POINT_COORD_COMPAT = 1`，否则 `gl_PointSize` / `gl_PointCoord` 不被支持、原版 `debug_point` 编译失败——
它是 `ShaderManager` 必需着色器，失败会让 `apply()` 抛异常、资源包被整体移除、UI 黑屏。失败时把
`spvc_context_get_last_error_string(context)` 附在 `"Couldn't compile HLSL: …"` 后。

顶点着色器额外用 `spvc_compiler_hlsl_add_vertex_attribute_remap` **按 SPIR-V location** 指定语义名：
`location 0 → "POSITION"`，`location n → "TEXCOORD${n-1}"`。必须按 location 而非 GLSL 声明顺序：`text.vsh`
声明 `Position, Color, UV0`，而顶点格式是 `Position, UV0, Color`，按声明顺序会让 `Color` 拿到 `TEXCOORD0`、
`UV0` 拿到 `TEXCOORD1`，与 input layout 正好互换 → GUI 文字消失或错乱。

`compile()` 最后再生成一份 `semanticNames`：
`for (i in 0 until vertex.inputs().size) semanticNames.add(if (i == 0) "POSITION" else "TEXCOORD${i - 1}")`，
基准是 **SPIR-V unique inputs 数量**、不是 `vertexInputNames`，才与上一步 remap 条目数对齐。

## 8. 阶段四：HLSL 补丁（坐标系与行序）

原版 GLSL 按 GL 约定（Y 向上、纹理 v=0 在底部），D3D12 raster 目标第 0 行是屏幕顶部。补丁全部是对生成的
HLSL 文本做正则/字符串插入，**不改 SPIR-V**。

### 8.1 顶点 Y 翻转

`isAtlasBake = pipelineLocation.contains("animate_sprite")`，`needsYFlip = isAtlasBake || flipY`。命中时用
`Regex("(?m)^([ \\t]*gl_Position\\s*=\\s*[^;]+;)$")` 在每个 `gl_Position` 赋值后插入
`gl_Position.y = -gl_Position.y;`；一个都找不到时只 warn
`no gl_Position assignment found in <…> vertex HLSL, Y-flip NOT injected`。

`flipY` 的来源：GUI 离屏 pass（有深度附件、usage 为 GuiItemAtlas / PictureInPicture）的正交投影带
`invertY=true`，整条 pass 的几何都要翻。判据必须「整个 pass 一起翻」，**不能**按管线名白名单：漏掉一条管线
就会翻与不翻混在一起，物品栏里的玩家模型会「头在底下、腿在上面」。
`animate_sprite` 则是无条件翻：它的用途（`TextureAtlas.uploadInitialContents` / `SpriteContents.drawToAtlas`）
没有深度附件、被上面那套判据排除，必须单独识别。

### 8.2 lightmap 翻转

`pipelineLocation.contains("lightmap") && !flipY` 时，在片元 HLSL 的 `void frag_main()\n{` 之后插入
`texCoord.y = 1.0f - texCoord.y;`；找不到 needle 时 warn `"[FIX] WARN: frag_main needle not found for <…>"`、不注入。

### 8.3 全屏 blit 翻转

判据是 `pipeline.shaders[ShaderType.VERTEX]?.toString() == SCREEN_QUAD_SHADER`
（`"minecraft:core/screenquad"`）且 `!flipY`、不含 `lightmap`、且片元里命中
`SAMPLES_WITH_TEXCOORD = Regex("Sample(?:Level|Bias|Grad|Cmp(?:Level)?)?\\s*\\([^)]*\\btexCoord\\b")`，命中则插入
`texCoord.y = 1.0f - texCoord.y;`。理由：raster 目标第 0 行 = 画面顶部、原版 texCoord 的 v=0 在底部，
raster→raster 直接拷会上下镜像；`blit_depth` / `blit_depth_during_depth_bounds` 还写深度附件，镜像深度会让云按
翻转的地形深度剔除（云上出现上下颠倒的地形空洞）。只对「用 `texCoord` 采样」的片元生效——用 `gl_FragCoord` /
`texelFetch` 的 blit 是逐像素原地拷贝，翻了反而错。

## 9. 阶段五：DXC 编译与缓存

### 9.1 编译参数

原生 `compileHlslToDxil(utils, compiler, source, sourceLen, entry, target, outDxil)` 传
`-T <target>  -E <entry>  -HV 2018  -O3`。图形管线固定 `entry = "main"`、`target = "vs_" + profile` /
`"ps_" + profile`，`profile` 来自 `graphicsShaderModelProfile()`（成功时恒 `"6_9"`）。失败时把
`IDxcResult::GetErrorBuffer` 的**全文**作为错误消息上抛；Java 侧只留首行（`firstLine`），避免一行日志几屏长。

### 9.2 进程级 DXC 单例

`static CachedDxc& cached = *new CachedDxc();   // 故意永不 delete`——**故意不漏析构**：析构里 `Release()`
COM 对象会在 CRT 退出阶段触发 ucrtbase fail-fast（0xc0000409）。dll 搜索链（日志原样）
`MCDR_DXC_DIR / MCDR.dll dir / Windows SDK`；找不到时 `caps.dxcNote = "dxcompiler.dll not found (...)"`。

### 9.3 DXIL 磁盘缓存

| 项 | 值 |
| --- | --- |
| 目录 | `MCDR_DXIL_CACHE_DIR`，否则 `<MCDR.dll 所在目录>\MCDR_dxil_cache` |
| 文件名 | `<key>.dxil` |
| 键 | FNV-1a 64（初值 `1469598103934665603`、乘数 `1099511628211`） |
| 键内容（依序） | dxcompiler.dll 版本标签（大小 + 最后写入时间）、profile、entry、HLSL 源码 |
| 单条读入上限 / 关闭 | `64 << 20`（64 MB） / `MCDR_DXIL_CACHE=0` |

把 dll 的「大小 + mtime」掺进键等价于「换 DXC 就整体失效」，无需手动清缓存。动机见注释：DXC 单次 0.4~3 s
且在主线程，一个会话几十个着色器，启动累计停顿十几秒。日志：命中 `(disk cache hit)`、未命中
`(took <N> ms)`。缓存文件的清理 / 容量上限**代码中未明确**（只限制单条读入大小）。

### 9.4 HLSL 落盘（可选诊断）

`D3D12Settings.dumpHlsl`（配置项 `[debug] dumpHlsl`）或环境变量 `MCDR_DUMP_HLSL=1` 时，把每对结果写到
`dxrmc/hlsl/vs_<hex hashCode>.hlsl` 与 `ps_<hex hashCode>.hlsl`，最多 60 对、超出不再写。用 hashCode 是为了
不引入 id→路径映射，代价是只能靠内容比对找回管线。

## 10. 绑定模型：寄存器分配

### 10.1 三层映射

`D3D12BindGroupEntry` 列表下标 `i` 同时是：SPIR-V 的 binding decoration（`rebind` 写回）、HLSL 资源声明
（SPIRV-Cross 按 binding 生成）、原生根签名/输入布局里的 `PipelineDesc::Binding.reg = i`。

### 10.2 寄存器类别

| type | 含义 | 寄存器 | 根签名落点 |
| --- | --- | --- | --- |
| 0 | `UNIFORM_BUFFER` | `b{i}` | **root descriptor CBV**（每个 CBV 一个根参数） |
| 1 | `SAMPLED_IMAGE` | `t{i}` + `s{i}` | 单个 descriptor table + **static sampler** |
| 2 | `TEXEL_BUFFER` | `t{i}` | 同一个 descriptor table（**不配 sampler**） |

`b` / `t` / `s` 是各自寄存器空间但都沿用同一下标 `i`：第 0 条是 UBO、第 1 条是纹理时 HLSL 里是 `b0` 与 `t1`。
**CBV 寄存器号不稠密**（`cbvRegs.push_back(b.reg)` 保留原号），只有**根参数索引**按出现顺序稠密编号；
SRV 表固定放在根参数索引 `cbvCount`（所有 CBV 之后），故「SRV 表参数号 = CBV 个数」是根签名不变量。
图形管线**没有 UAV（`u`）**：`u0` 只出现在 compute 最小通路，根签名固定「root UAV `u0` + 8 个根常量 `b0`」，
代码中未把它扩展成按管线生成。

### 10.3 static sampler 的字段

`type == 1` 的每个条目生成一个 `D3D12_STATIC_SAMPLER_DESC`：`Filter = MIN_MAG_MIP_POINT`、
`AddressU/V/W = WRAP/WRAP/WRAP`、`MipLODBias = 0.0f`、`MaxAnisotropy = 1`、`ComparisonFunc = NEVER`、
`BorderColor = OPAQUE_BLACK`、`MinLOD = 0.0f`、`MaxLOD = D3D12_FLOAT32_MAX`、`ShaderRegister = b.reg`
（= 条目下标，与 SRV 同号）、`ShaderVisibility = ALL`。

地址模式必须用 `WRAP` 而不能用 `CLAMP`：原版 `AbstractTexture` 默认采样器是 REPEAT/REPEAT，而天气粒子
（雨/雪）的 `v = y*0.25 + offset` 能到 16~40；`CLAMP` 会把整根雨柱采成边缘像素、渲染成贯穿屏幕的实心竖条。
`WRAP` 下正常平铺，而对 UV 已在 [0,1] 内的纹理（图集、字体、lightmap）两者等价。

### 10.4 sampler 的静态/动态之分

- **静态**：图形管线的 sampler 全部来自 PSO 的 static sampler 表，不占根参数、不写描述符堆、不占 sampler heap。
  这是主路径。
- **动态**：`D3D12GpuSampler`（`GpuSampler` 的 D3D12 实现）会调 `ID3D12CreateSampler` /
  `ID3D12CreateSamplerWithMipBias`，在原生 sampler heap（`kSamplerHeapSize = 2048` 槽，按槽分配/归还）真建
  `D3D12_SAMPLER_DESC`。但图形 pass 的 `pushDescriptors` 只把 **view 句柄**传给原生
  （`types[i]=1, views[i]=view.handle()`），sampler 对象本身**不参与绑定**：动态采样器目前只被创建与持有，
  绘制路径仍走 static sampler（HLSL 侧最终如何让参数生效、是否需要未来动态表——代码中未明确）。
- `D3D12GpuSampler` 字段映射：address `0 = REPEAT(WRAP)`、`1 = CLAMP_TO_EDGE`；filter 按 ordinal
  （`0 = NEAREST`、`1 = LINEAR`）；`maxLod` 缺省 16。`mipLodBias != 0` 时先调
  `ID3D12CreateSamplerWithMipBias`，返回 0（本地 DLL 没有这条导出）再退回普通版本；bias 只在超分
  （DLSS / DLAA）时非 0，原生把 `[-16, 15.99]` 之外与 NaN 一律按 0。句柄为 0 抛
  `IllegalStateException("dx12CreateSampler returned a null handle")`。

### 10.5 CBV 的 256 字节约束

D3D12 要求 CBV 的 GPU 地址按 256 字节对齐（root descriptor 同一条约束），两处共同保证：**分配侧** uniform 缓冲
统一按 256 对齐分配，保证任意切片做 CBV 时 `SizeInBytes` 向上取整到 256 仍不越界（注释：资源分配粒度本就
64 KB，没有额外浪费）；**提交侧** `pushDescriptors` 显式拦截
`constexpr UINT64 kCbvAlignment = 256; if (va % kCbvAlignment != 0) return mcdr::err("pushDescriptors", "… is not 256-byte aligned …");`。
不放行的话，调试层只报 "pBufferLocation must be 256-byte aligned"，零售驱动下则是着色器读到错位数据
（变换/贴图乱掉），排查成本远高于一次报错。Java 侧原样带进异常：
`throw IllegalStateException("dx12PushDescriptors failed: ${MCDR_ABI.D3D12LastError()}")`。

## 11. 缓存层次

| 层 | 键 | 容器 | 失效 |
| --- | --- | --- | --- |
| 着色器模块（SPIR-V + 反射） | `ShaderCompilationKey(id, type, defines)` | `HashMap` | `clearPipelineCache()` 或资源重载 |
| 已编译管线（非 flipY） | `RenderPipeline` 实例 | `IdentityHashMap` | 同上 |
| 已编译管线（flipY 变体） | `FlipYKey(pipeline, flipY)` | `HashMap` | 同上 |
| DXIL 字节码 | FNV-1a(dll 版本, profile, entry, 源码) | 磁盘 `<key>.dxil` | 换 dll 自动失效；`MCDR_DXIL_CACHE=0` 关闭 |

`getOrCompileShader` 是 `computeIfAbsent`，编译失败也**缓存一个 `INVALID`**——同一条坏着色器不会每次重跑 shaderc。

`getOrCompilePipeline` 命中时**先验活**：`isValid()` 定义为 `handle != 0`，而原版在资源重载（例如切换
「高对比度」资源包）时会把 `CompiledRenderPipeline` 全部 `close()`，缓存里握着同一实例、`close()` 后 `handle`
已置 0。若不验活直接返回该对象，`setPipeline` 就会抛 "Pipeline … is not valid" 且**一条 COMPILE FAILED 都不会打**
（因为根本没编译）；所以失效就丢掉重编。`clearPipelineCache()` 先 `close()` 每条管线并清空两个管线缓存，再
`close()` 每个着色器模块并清空 `shaderCache`；`close()` 幂等（先取走句柄再归零），因为原版
`FrontendGpuDevice.close()` 也会逐条 close 同一批对象，不归零就是同一条 `Dx12Pipeline*` 被销毁两次（堆损坏）。

## 12. 失败路径

### 12.1 不致命：单条管线失效

以下情况让某条管线 `handle = 0`（`isValid() == false`）、打 `COMPILE FAILED`，但**不中断**其它管线：

| 情况 | 日志 |
| --- | --- |
| 顶点/片元模块是 `INVALID` | `"{} COMPILE FAILED (vertex\|fragment): shader {} invalid"` |
| `rebind` 缺输入/UBO/sampler、类型不符 | `"{} COMPILE FAILED (compile): {}"`（`ShaderCompileException`） |
| GLSL 解析失败 | `"Couldn't compile {} shader {}: {}"`，随后同上 |
| DXC 编译 HLSL 失败 | `"{} COMPILE FAILED (native): dx12CreateGraphicsPipeline returned 0"`（原因见原生 last error） |
| 根签名 / `CreateGraphicsPipelineState` 失败 | 同上 |
| 取不到任何源 | `"Couldn't find source for {} shader ({})"` |

随后 `setPipeline` 处是**硬失败**：
`if (!compiled.isValid()) throw IllegalStateException("Pipeline ${base.info().location} is not valid (shader compilation failed)")`
——刻意对齐 `VulkanRenderPass.setPipeline` 的严格语义（GL 后端不校验）。

### 12.2 可选增强失败：降级但不失败

- **线宽变体**：拓扑是 `LINES` / `DEBUG_LINES` 时额外建一条 `TRIANGLES` PSO（D3D12 没有宽线，`LINELIST` 恒
  1px；原版每段 4 顶点 / 6 索引恰好摊成一条带子）。建失败只 warn
  `"line quad variant for {} failed; lines stay 1px"`。
- **meshlet 变体**：仅当 `D3D12Settings.meshTerrain` 且管线名含 `terrain`、不含 `shadow`。失败逐级降级：
  AS 解析不到 uniform → 退回不剔除的 MS；MS/AS 编译失败 → 再退一次；带 AS 的 PSO 建失败 → 试不带 AS 的同一份
  MS。全失败只 warn `"meshlet variant for {} failed"`，普通 PSO 照用。

### 12.3 致命：整条图形管线不可用

`createGraphicsPipeline` 一进来就问 `graphicsShaderModelProfile()`，返回空串直接报错、**不建任何 PSO**。
`sm69RequirementText()` 把三种缺法分开说：

| 缺什么 | 文案要点 |
| --- | --- |
| 没有 DXC | `shader model 6.9 is required and there is no SM 5.1 fallback any more, but dxcompiler.dll was not found (MCDR_DXC_DIR / MCDR.dll dir / Windows SDK)` |
| DXC 太旧 | `… but this dxcompiler.dll cannot compile 6_9 (profile probe: <profile>; needs DXC 1.9.2502 or newer)` |
| 设备 core 太旧 | `… but the device reports SM <x> (Agility SDK 619's D3D12Core.dll must sit next to MCDR.dll and be opted in)` |

这是「必须能编译成功的地方」：发行包因此必须带 `dxcompiler.dll` + `dxil.dll` + Agility 的 `D3D12Core.dll`，
缺任何一个，一条图形管线都建不出来。

## 13. 关键常量与魔法数字

| 常量 / 数值 | 位置 | 含义 |
| --- | --- | --- |
| `0x402000` | shaderc target env | Vulkan 1.2 |
| `MAX_INCLUDE_DEPTH = 16` / `MAX_EXPANDED_CHARS = 8 * 1024 * 1024` | include 展开 | 递归上限 / 展开后总长上限 |
| `SPVC_COMPILER_OPTION_HLSL_SHADER_MODEL = 51` | HLSL 生成 | SPIRV-Cross 的 HLSL 语法档 |
| `0 / 1` 与 `3 / 4` 与 `30 / 33` | 反射/编译 | vertex·fragment 阶段号 / STAGE_INPUT·OUTPUT / LOCATION·BINDING |
| `1 / 3 / 5` | 反射 | 2D / Cube / Buffer 维度 |
| `"6_9"` | `graphicsShaderModelProfile` | 图形管线唯一可用 profile |
| `"ps_6_0"` / `"ms_6_9"` / `"as_6_9"` | mesh 变体 | PS / MS / AS 的 profile；`"ms_6_9"` 也是 `probeDxc` 冒烟目标 |
| `1469598103934665603` / `1099511628211` | DXIL 缓存 | FNV-1a 64 初值与乘数 |
| `64 << 20` | DXIL 缓存 | 单条读入上限 |
| `256` | CBV / uniform 分配 | CBV 地址对齐 |
| `2048` | `kSamplerHeapSize` | sampler 堆槽数 |
| `16` / `60` | 输入布局 / HLSL 落盘 | 顶点 slot 上限 / 最多写 60 对 |
| `2` / `512` / `8` | `D3D12StackSampler` | 采样间隔 ms / 栈容量 / 记录深度 |

## 14. 不变量清单

1. `#version` 必须是预处理后的第一条指令，所有 `#define` 插在它之后。
2. `rebind` 必须发生在克隆体上；缓存里的原始 SPIR-V 永不改写。
3. 绑定条目下标 == SPIR-V binding == HLSL 寄存器号（`b`/`t`/`s` 共用同一下标）。
4. CBV 根参数索引稠密（0..cbvCount-1）而寄存器号不稠密；SRV 表参数索引恒为 `cbvCount`。
5. 每个 `SAMPLED_IMAGE` 条目必须有同号 `t{i}` 与 `s{i}`；`TEXEL_BUFFER` 只有 `t{i}`。
6. 顶点属性语义名按 **SPIR-V location** 分配（0 → POSITION，n → TEXCOORD(n-1)），与 HLSL 声明顺序无关。
7. `semanticNames` 可短于 input layout 元素数，原生补 `TEXCOORD<n>`；多余语义名被忽略。
8. 输入布局 stride 必须**逐 slot** 算：优先元素自带 stride，否则取该 slot 内 `offset + 元素字节数` 的上界；
   把各 slot 最大值当公共 stride 会让逐实例属性错位。
9. 每个 CBV 的 GPU 地址必须 256 字节对齐。
10. 双 PSO 必须**两条都建成功**：`withDepth`（DSV = `D32_FLOAT`）与 `withoutDepth`（DSV = `UNKNOWN`），
    任一失败整条管线报错。
11. `withDepth` 的 `DepthEnable` / `DepthFunc` 必须跟随 `desc.hasDepth`：无深度管线若被强制开深度测试会拿到
    无效 `depthFunc` 而全黑。
12. 语义名不能以数字结尾：`"TEXCOORD0"` 会被拆成 `SemanticName="TEXCOORD"` + `SemanticIndex=0`，且
    `SemanticName` 必须指向持久化内存（不能是局部 `std::string` 的 `c_str()`）。
13. `isValid()` 等价于 `handle != 0`；缓存命中前必须验活。
14. `close()` 必须幂等（先取走句柄再归零）。
15. 一条 `Dx12Pipeline*` 只能有一个所有者：`D3D12CompiledRenderPipeline` 负责销毁，meshlet / line-quad 变体
    各自单独释放。

补充（与第 7、12 条相关）：`buildNativeDesc` 在 `semanticNames.size < inputElements.size` 时补
`"TEXCOORD${size}"`，因为存在「HLSL 只有 1 个输入、顶点格式有 2 个元素」的 GUI 管线。

## 15. D3D12StackSampler 的用途

名字容易误解：它**不是**纹理采样器，而是渲染线程的 CPU 调用栈采样器（poor-man's profiler）。
`INTERVAL_MS = 2L`、`STACK_CAP = 512`、`STACK_DEPTH = 8`：`attach()` 在渲染线程登记采样目标，`start()` 起
daemon 线程 `backend-stack-sampler`（`MAX_PRIORITY`）每 2 ms 取一次 `target.stackTrace`，跳过自身帧与
`D3D12Prof*` 埋点后统计栈顶 `类名.方法名`；`OURS` 统计 `$CLASSPATH.` 前缀占比，top3 额外存 8 层调用链，
`report(topN)` 输出 `stack sample n= ours= mc= | k=v…` 与 `stack sample trace …`。存在理由：分段计时把
「MC 窗口内的 JNI/后端开销」混在一起无法归因，本类能区分 `MCDR_ABI.*` 与 `net.minecraft.*` /
`com.mojang.blaze3d.*`。代价是 `Thread.getStackTrace()` 会强制安全点、开销约 1~2%，故只在性能剖析开关打开时启用。

## 16. 文件导航

路径相对于 `Illusion\src\main\kotlin\dev\nts\expdxr\`：

| 文件 | 职责 |
| --- | --- |
| `d3d12\render\shader\D3D12ShaderCompiler.kt` | GLSL 预处理（define/include）、shaderc 编译、绑定条目构造、HLSL 补丁（Y / lightmap / blit） |
| `d3d12\render\shader\D3D12IntermediaryShaderModule.kt` | SPIR-V 容器与反射、`rebind` 重编号、克隆、SPIRV-Cross → HLSL |
| `d3d12\render\shader\D3D12CompiledShader.kt` | 一条管线的编译产物（HLSL 对、绑定条目、输入名、语义名） |
| `d3d12\render\shader\D3D12FallbackShaderSource.kt` | 首次资源重载前，用捕获的 vanilla `ResourceProvider` 作后备源 |
| `d3d12\render\shader\D3D12GpuSampler.kt` | `GpuSampler` 的 D3D12 实现（含 mip bias 与幂等关闭） |
| `d3d12\render\shader\D3D12StackSampler.kt` | 渲染线程 CPU 栈采样的诊断工具（不是纹理采样器） |
| `d3d12\render\adapter\D3D12Device.kt` | 着色器/管线缓存、`buildNativeDesc`、`compilePipeline`、mesh 与线宽变体 |
| `d3d12\backend\D3D12CompiledRenderPipeline.kt` | 已编译管线（PSO 句柄、绑定推导、变体管理、幂等 `close`） |
| `d3d12\backend\D3D12BindGroupEntry.kt` | 绑定条目类型（UBO / 采样纹理 / texel buffer） |
| `d3d12\backend\D3D12RenderPassBackend.kt` | pass 内 `setPipeline`、`pushDescriptors`、uniform 下标映射 |
| `d3d12\backend\D3D12PipelineBridge.kt` | `location` → `RenderPipeline` 的登记与查回 |
| `d3d12\render\advanced\mesh\D3D12MeshletShader.kt` | 把地形顶点着色器包装成 `ms_main` / `as_main` |
| `d3d12\extern\MCDR_ABI.kt` | FFM 绑定（含 `D3D12CompileShaderDxc` 的两次调用尺寸探测契约） |

原生侧（`MCDR\src\`）：`core\d3d12_device.cpp` 的 `compileWithDxc` / `compileHlslToDxil` / DXIL 磁盘缓存 /
`graphicsShaderModelProfile` / `createGraphicsPipeline`；`c_abi\abi_mesh.cpp` 的 `mcdr_dx12CompileShaderDxc`；
`advanced\d3d12_mesh_base.cpp` 的 `meshPipelineSupported` 与 mesh 根签名。

## 17. 文档维护

- 本文每条都能在源码里定位。改动下列任一处后请同步本文：`D3D12ShaderCompiler` 的正则/插入点（Y 翻转、
  lightmap、blit）；`rebind` 的编号规则或 `addToBindGroup` 的类型判定；原生根签名的「CBV 稠密 / SRV 表在
  `cbvCount`」布局；`graphicsShaderModelProfile()` 的门槛（profile 字符串、DXC 版本下限）；DXIL 磁盘缓存的
  键构成；第 13 节的常量表。
- 新增一种「HLSL 文本补丁」时，请同时列出**判据**与**不注入时的日志**，否则下次只能靠 grep 猜哪条管线被改过。
- 新增绑定类型（例如 UAV）前，先确认根签名、`pushDescriptors` 与 `D3D12BindGroupEntry.Type` 三处能同时表达，
  否则会出现「编译通过但运行期读错寄存器」。
- 文中标注「代码中未明确」的地方是已知文档空白、不是疏漏；补全前请先改代码注释。
