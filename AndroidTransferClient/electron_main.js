const { app, BrowserWindow } = require('electron');
const path = require('path');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');

let mainWindow;
let flaskProcess;

// 启用详细日志记录
const LOG_DIR = path.join(os.homedir(), 'Documents', 'AndroidTransfer', 'logs');
const LOG_FILE = path.join(LOG_DIR, `electron-${Date.now()}.log`);

// 创建日志目录
if (!fs.existsSync(LOG_DIR)) {
    fs.mkdirSync(LOG_DIR, { recursive: true });
}

// 日志记录函数
function log(level, ...args) {
    const timestamp = new Date().toISOString();
    const message = `[${timestamp}] [${level}] ${args.join(' ')}\n`;
    
    // 输出到控制台
    console.log(message.trim());
    
    // 写入日志文件
    try {
        fs.appendFileSync(LOG_FILE, message);
    } catch (err) {
        console.error('写入日志失败:', err);
    }
}

// 启动时记录系统信息
log('INFO', '='.repeat(60));
log('INFO', 'Android Transfer 启动');
log('INFO', '='.repeat(60));
log('INFO', 'Electron 版本:', process.versions.electron);
log('INFO', 'Node 版本:', process.versions.node);
log('INFO', 'Chrome 版本:', process.versions.chrome);
log('INFO', '操作系统:', os.platform(), os.release());
log('INFO', '架构:', os.arch());
log('INFO', '内存:', Math.round(os.totalmem() / 1024 / 1024 / 1024), 'GB');
log('INFO', '日志文件:', LOG_FILE);
log('INFO', '='.repeat(60));

function createWindow() {
    log('INFO', '创建主窗口...');
    
    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            // 防止内存泄漏的设置
            webSecurity: true,
            enableRemoteModule: false,
            // 限制渲染进程的内存使用
            backgroundThrottling: true
        },
        icon: path.join(__dirname, 'icon.png')
    });

    log('INFO', '主窗口创建成功');

    // 防止内存泄漏：清理旧的监听器
    mainWindow.webContents.removeAllListeners('did-fail-load');
    mainWindow.webContents.removeAllListeners('crashed');

    // 记录控制台日志
    mainWindow.webContents.on('console-message', (event, level, message, line, sourceId) => {
        const levelMap = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
        log(levelMap[level] || 'INFO', `[渲染进程] ${message}`);
    });

    // 等待Flask启动后加载
    log('INFO', '等待 Flask 启动...');
    setTimeout(() => {
        log('INFO', '加载 URL: http://127.0.0.1:9500');
        mainWindow.loadURL('http://127.0.0.1:9500').catch(err => {
            log('ERROR', '加载URL失败:', err);
            console.error('加载URL失败:', err);
        });
    }, 3000);

    // 防止内存泄漏：监听并处理渲染进程崩溃
    mainWindow.webContents.on('crashed', (event, killed) => {
        log('ERROR', '渲染进程崩溃! killed:', killed);
        console.error('渲染进程崩溃');
        // 可以选择重启窗口
        if (mainWindow) {
            log('INFO', '尝试重新加载窗口...');
            mainWindow.reload();
        }
    });
    
    // 监听页面加载事件
    mainWindow.webContents.on('did-finish-load', () => {
        log('INFO', '页面加载完成');
    });
    
    mainWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription) => {
        log('ERROR', '页面加载失败:', errorCode, errorDescription);
    });

    mainWindow.on('closed', function () {
        log('INFO', '主窗口关闭');
        // 清理窗口资源
        if (mainWindow) {
            mainWindow.removeAllListeners();
            mainWindow.webContents.removeAllListeners();
        }
        mainWindow = null;
    });
    
    // 防止内存泄漏：定期触发垃圾回收（如果可用）
    if (global.gc && !app.isPackaged) {
        setInterval(() => {
            global.gc();
            console.log('手动触发垃圾回收');
        }, 60000); // 每分钟一次
    }
}

function startFlask() {
    log('INFO', '启动 Flask 后端...');
    
    // 判断是开发环境还是生产环境
    const isDev = !app.isPackaged;
    log('INFO', '运行模式:', isDev ? '开发环境' : '生产环境');
    
    // 配置环境变量（重要！让Flask能找到ADB）
    const env = Object.assign({}, process.env);
    
    // 添加常见的ADB路径到PATH
    const adbPaths = [
        '/opt/homebrew/bin',           // Homebrew (M1 Mac)
        '/usr/local/bin',               // Homebrew (Intel Mac)
        path.join(process.env.HOME || '', 'Library/Android/sdk/platform-tools'),  // Android SDK
        '/usr/bin',
        '/bin'
    ];
    
    // 合并PATH
    env.PATH = adbPaths.join(':') + ':' + (env.PATH || '');
    
    log('INFO', 'Flask 环境变量 PATH:', env.PATH);
    console.log('Flask 环境变量 PATH:', env.PATH);
    
    if (isDev) {
        // 开发环境：直接运行Python脚本
        log('INFO', '启动 Python 脚本: python3 app.py');
        flaskProcess = spawn('python3', ['app.py'], {
            cwd: __dirname,
            env: env  // 传递环境变量
        });
    } else {
        // 生产环境：运行打包后的可执行文件
        const exePath = path.join(process.resourcesPath, 'app', 'app');
        log('INFO', '启动打包后的可执行文件:', exePath);
        flaskProcess = spawn(exePath, [], {
            env: env  // 传递环境变量
        });
    }
    
    log('INFO', 'Flask 进程 PID:', flaskProcess.pid);

    // 防止内存泄漏：限制日志输出，只保留最近的日志
    const MAX_LOG_LINES = 100;
    let stdoutLines = [];
    let stderrLines = [];
    
    flaskProcess.stdout.on('data', (data) => {
        const lines = data.toString().split('\n');
        stdoutLines.push(...lines);
        
        // 只保留最近的 100 行日志
        if (stdoutLines.length > MAX_LOG_LINES) {
            stdoutLines = stdoutLines.slice(-MAX_LOG_LINES);
        }
        
        // 输出到控制台（可选，打包后可以注释掉）
        if (!app.isPackaged) {
            console.log(`Flask: ${data.toString().substring(0, 500)}`); // 限制长度
        }
    });

    flaskProcess.stderr.on('data', (data) => {
        const lines = data.toString().split('\n');
        stderrLines.push(...lines);
        
        // 只保留最近的 100 行日志
        if (stderrLines.length > MAX_LOG_LINES) {
            stderrLines = stderrLines.slice(-MAX_LOG_LINES);
        }
        
        console.error(`Flask错误: ${data.toString().substring(0, 500)}`); // 限制长度
    });

    flaskProcess.on('close', (code) => {
        log('INFO', `Flask 进程退出，代码: ${code}`);
        console.log(`Flask进程退出，代码: ${code}`);
        
        // 清理日志
        stdoutLines = [];
        stderrLines = [];
    });
    
    flaskProcess.on('error', (err) => {
        log('ERROR', 'Flask 进程错误:', err);
        console.error('Flask进程错误:', err);
    });
    
    log('INFO', 'Flask 后端启动完成');
}

app.on('ready', () => {
    startFlask();
    createWindow();
});

app.on('window-all-closed', function () {
    // 清理 Flask 进程
    if (flaskProcess) {
        try {
            flaskProcess.kill('SIGTERM');
            // 等待一段时间后强制杀死
            setTimeout(() => {
                if (flaskProcess && !flaskProcess.killed) {
                    flaskProcess.kill('SIGKILL');
                }
            }, 2000);
        } catch (err) {
            console.error('关闭Flask进程失败:', err);
        }
        flaskProcess = null;
    }
    
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', function () {
    if (mainWindow === null) {
        createWindow();
    }
});

app.on('quit', () => {
    // 清理所有资源
    if (flaskProcess) {
        try {
            flaskProcess.kill('SIGTERM');
            setTimeout(() => {
                if (flaskProcess && !flaskProcess.killed) {
                    flaskProcess.kill('SIGKILL');
                }
            }, 1000);
        } catch (err) {
            console.error('关闭Flask进程失败:', err);
        }
        flaskProcess = null;
    }
    
    // 清理主窗口
    if (mainWindow) {
        mainWindow.removeAllListeners();
        mainWindow.webContents.removeAllListeners();
        mainWindow = null;
    }
});

// 防止内存泄漏：处理未捕获的异常
process.on('uncaughtException', (error) => {
    log('ERROR', '未捕获的异常:', error);
    console.error('未捕获的异常:', error);
});

process.on('unhandledRejection', (reason, promise) => {
    log('ERROR', '未处理的Promise拒绝:', reason);
    console.error('未处理的Promise拒绝:', reason);
});

// 启动时记录
app.on('ready', () => {
    log('INFO', 'Electron app ready 事件触发');
});

