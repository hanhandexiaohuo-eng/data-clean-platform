#!/usr/bin/env node
/**
 * 敏感信息扫描：发现疑似明文凭据时以非 0 退出码阻断构建。
 *
 * 用法（Node 18+，无需安装依赖）：
 *   node scripts/scan-secrets.mjs
 *
 * 说明：
 *   - 只扫描源码、SQL、配置模板与文档，跳过 node_modules/target/dist/uploads/.git；
 *   - 命中占位符（CHANGE_ME、your_、${VAR}、****）不会误报；
 *   - 输出只包含文件路径与行号，不打印命中的具体内容。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, extname } from 'node:path'

const ROOT = process.cwd()
const SKIP_DIRS = new Set(['node_modules', 'target', 'dist', 'uploads', '.git', '.idea', '.vscode', 'logs', 'build', 'backups', 'certs', 'data'])
const TEXT_EXT = new Set([
  '.java', '.xml', '.yml', '.yaml', '.properties', '.js', '.mjs', '.vue', '.ts',
  '.sql', '.md', '.json', '.example', '.env', '.conf', '.txt', '.sh', '.ps1',
])

const RULES = [
  { name: '私钥文件内容', re: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/ },
  { name: 'OpenAI 风格密钥', re: /\bsk-[A-Za-z0-9]{20,}\b/ },
  { name: 'AWS AccessKey', re: /\bAKIA[0-9A-Z]{16}\b/ },
  { name: 'GitHub Token', re: /\bghp_[A-Za-z0-9]{20,}\b/ },
  { name: 'Google API Key', re: /\bAIza[0-9A-Za-z\-_]{35}\b/ },
  { name: 'JDBC 明文密码', re: /jdbc:[^"'\s]*[?&]password=[^&\s"']+/i },
  {
    name: '硬编码 password',
    re: /(?:password|passwd|pwd)\s*[:=]\s*["'][^"']{6,}["']/i,
    allow: /(CHANGE_ME|your_|example|placeholder|\$\{|\$[A-Za-z_]|\*{4}|<.*>)/i,
  },
  {
    name: '硬编码 JWT secret',
    re: /(?:jwt[._-]?secret)\s*[:=]\s*[^\s"'#$]{8,}/i,
    allow: /(CHANGE_ME|your_|example|placeholder|\$\{)/i,
  },
  {
    name: '硬编码 api key',
    re: /api[_-]?key\s*[:=]\s*["'][A-Za-z0-9_\-]{16,}["']/i,
    allow: /(CHANGE_ME|your_|example|placeholder|redacted|\$\{|\*{4})/i,
  },
]

const findings = []

function walk(dir) {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue
    const full = join(dir, entry)
    let stat
    try {
      stat = statSync(full)
    } catch {
      continue
    }
    if (stat.isDirectory()) {
      walk(full)
      continue
    }
    if (stat.size > 2 * 1024 * 1024) continue
    // 本地 .env 已被 .gitignore 排除，仅用于个人联调，不纳入交付物扫描；*.example 仍会被扫描
    if (entry === '.env' || (entry.startsWith('.env.') && !entry.includes('example'))) continue
    const isDockerfile = entry === 'Dockerfile' || entry.endsWith('.Dockerfile')
    if (!TEXT_EXT.has(extname(full).toLowerCase()) && !entry.startsWith('.env') && !isDockerfile) continue

    const rel = relative(ROOT, full)
    let content
    try {
      content = readFileSync(full, 'utf8')
    } catch {
      continue
    }
    content.split(/\r?\n/).forEach((line, index) => {
      if (line.trim().startsWith('//') || line.trim().startsWith('#')) return
      for (const rule of RULES) {
        if (rule.allow && rule.allow.test(line)) continue
        if (rule.re.test(line)) {
          findings.push({ file: rel, line: index + 1, rule: rule.name })
        }
      }
    })
  }
}

walk(ROOT)

if (findings.length === 0) {
  console.log('[scan-secrets] 未发现疑似明文凭据')
  process.exit(0)
}

console.error('[scan-secrets] 发现疑似明文凭据（仅输出位置，不输出内容）：')
for (const f of findings) {
  console.error(`  - ${f.file}:${f.line}  [${f.rule}]`)
}
console.error(`\n共 ${findings.length} 处。请改为环境变量或占位符后重试。`)
process.exit(1)
