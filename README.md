# 葵花码工坊（离线 WX_CODE）

最小可部署代码库：浏览器里输入网址、上传 Logo，服务端离线生成带 Logo 的葵花码 PNG。

**不调用微信生成接口**，不需要 AppID/AppSecret，不做短链、不保存上传内容或生成结果。生成后强制用原生解码器回读，链接完全一致才返回图片。

> **边界必须知道：** 已验证“离线编码 + 原生回读”。**尚未完成当前手机微信的实扫验收**，接口与页面都显式返回 `wechat_scan_verified: false`。图形码有容量上限，过长链接会明确报错而不是截断或改用普通二维码。

## 一键部署（Docker）

前提：Docker（本机已用 Docker 29 实测）与 2 GB 可用内存。

```bash
docker compose up -d --build
docker compose ps          # 等 STATUS 显示 (healthy)
```

打开 <http://localhost:8000>。换端口：`WXCODE_PORT=9000 docker compose up -d --build`。

镜像内包含：Python 3.13 + FastAPI、OpenJDK 17、librsvg、libseccomp2、编译好的 `wxcode-bridge.jar`，以及 8 个已校验的原生库/模型文件。

## 本机运行（不用 Docker）

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python scripts/fetch_runtime.py                  # 只下载 8 个固定版本文件并校验 SHA-256
mvn -f native/pom.xml -DskipTests package        # 产出 native/target/wxcode-bridge.jar
uvicorn app.server:app --port 8000
```

系统依赖：Linux amd64、Java 17+、`rsvg-convert`（`librsvg2-bin`）、`libseccomp2`、Maven（仅构建时）。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 前端页面 |
| GET | `/api/health` | `runtime_ready`、`code_type`、`wechat_scan_verified` |
| POST | `/api/generate` | `multipart/form-data`：`url` + `logo`，成功返回 `image/png` |

成功响应头携带 `X-Payload-Base64`（实际编码的规范化 URL）、`X-Code-Type: WX_CODE`、`X-Verification: native-roundtrip`、`X-WeChat-Verified: false`。

失败返回 `{"error": {"code", "message"}}`，状态码：`INVALID_URL` 400、`INVALID_LOGO` 400/413、`PAYLOAD_TOO_LARGE` 413、`CAPACITY_EXCEEDED` 422、`BUSY` 503、`RUNTIME_ERROR` 503。

约束：Logo 仅 PNG/JPEG/WebP、≤2 MiB、每边 ≤4096 px、非动图；请求体 ≤3 MiB；仅接受 http/https，最多 512 字节候选链接（实际容量更小，取决于内容和纠错级别）。

## 如何验证部署

### 1. 容器与健康检查

```bash
docker compose ps                                   # (healthy)
curl -s localhost:8000/api/health
# {"status":"ok","runtime_ready":true,"code_type":"WX_CODE","wechat_scan_verified":false}
```

### 2. 前端与生成（一条命令）

```bash
curl -s -o /tmp/code.png -w '%{http_code} %{content_type} %{size_download}\n' \
  -F 'url=https://www.alipay.com/' \
  -F 'logo=@examples/alipay-logo.png;type=image/png' \
  http://localhost:8000/api/generate
python3 -c "from PIL import Image; im=Image.open('/tmp/code.png'); print(im.size, im.format)"
# 200 image/png <字节数>
# (600, 600) PNG
```

再打开首页点“生成”：填入链接、拖入 Logo，右侧出现 600×600 预览与“实际编码地址”，可下载 PNG。

### 3. 自动化测试

```bash
pip install -r requirements-dev.txt
python -m pytest -q                        # 单元 + 接口 + 原生编解码
python -m pytest -q -m e2e                 # 真实浏览器端到端（需 playwright install chromium）
```

本机已实际执行：**34 项全部通过**（含真实浏览器完成“填链接→上传 Logo→生成→下载 600×600 PNG”，以及对 `https://www.alipay.com/` 的原生回读校验）。

## 目录

```
app/            FastAPI 服务、URL/Logo 校验、图像合成、断网沙箱子进程、静态前端
native/         固定版本 JNI 桥接源码与 Maven 构建
scripts/        8 个运行时文件下载校验、JNI 库预提取
tests/          单元、HTTP 契约、原生集成、浏览器端到端
examples/       示例 Logo 与生成结果（含用户原始样图，用于回归）
runtime.lock.json   运行时文件的固定来源与 SHA-256
```

## 安全与运行说明

- 每次编码/解码都通过 `app/sandbox.py` 启动，使用 libseccomp 禁止创建非 Unix 套接字；缺少 libseccomp 时拒绝运行（fail-closed），不会静默联网。
- 服务启动时校验 JAR、原生库和模型哈希；任一缺失即拒绝启动，不提供未校验图片。
- 生成的 SVG 外链图标被替换为内嵌 base64，中心 Logo 只作图像叠加，不改变外围数据码元。
- 容器以非 root 用户运行、丢弃全部 capabilities、`no-new-privileges`、限制进程数与内存。**不要启用 `read_only: true`**：JNA 需要可写且可执行的临时目录，只读根文件系统会导致原生生成失败（已实测）。
