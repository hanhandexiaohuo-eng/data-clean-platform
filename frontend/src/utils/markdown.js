import { marked } from 'marked'
import DOMPurify from '../vendor/dompurify.es.mjs'

/**
 * 渲染 AI 返回的 Markdown：先转为 HTML，再用 DOMPurify 白名单净化。
 * 避免模型输出中的脚本、事件属性或危险链接通过 v-html 被执行。
 */
export function renderMarkdown(content) {
  if (!content) {
    return ''
  }
  const html = marked.parse(content)
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'iframe', 'object', 'embed'],
    FORBID_ATTR: ['style'],
  })
}
