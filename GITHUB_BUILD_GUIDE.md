# FarmTradeApp 构建说明

## 在GitHub上自动构建APK

### 步骤一：注册GitHub账号
1. 访问 https://github.com
2. 点击 "Sign up" 注册账号（已有账号跳过）

### 步骤二：创建新仓库
1. 登录后点击右上角 "+" → "New repository"
2. 仓库名称填 `FarmTradeApp`
3. 选择 "Public"（公开）
4. 勾选 "Add a README file"
5. 点击 "Create repository"

### 步骤三：上传项目文件
**方法A：网页上传（最简单）**
1. 在仓库页面点击 "Add file" → "Upload files"
2. 将 FarmTradeApp 文件夹内的所有文件和文件夹拖入
3. 注意：`.github/workflows/build-apk.yml` 这个文件也要上传（它是自动构建的关键）
4. 点击 "Commit changes"

**方法B：使用GitHub手机App**
1. 下载 GitHub App
2. 打开你的仓库
3. 点击 "+" → "Import from device"
4. 选择 FarmTradeApp 文件夹

### 步骤四：等待自动构建
1. 上传完成后，GitHub会自动开始构建
2. 点击仓库顶部的 "Actions" 标签页
3. 看到一个名为 "Build APK" 的工作流正在运行
4. 等待约5-10分钟（首次构建需要下载依赖）

### 步骤五：下载APK
1. 构建完成后（绿色勾号✓），点击该次构建
2. 在页面底部找到 "Artifacts" 区域
3. 点击 "FarmTradeApp-debug-apk" 下载
4. 下载的是一个zip文件，解压后得到 `.apk` 文件
5. 将APK传到手机安装即可

## 注意事项
- 首次构建可能因依赖下载较慢，约10-15分钟
- 如果构建失败，查看错误日志排查问题
- 构建产物保留30天
- 每次推送代码会自动触发重新构建
