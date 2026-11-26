#!/usr/bin/env node
/**
 * 生成 10 万行合成 CSV，用于隔离环境的性能与质量统计验证。
 *
 * 用法：
 *   node scripts/perf/generate-synthetic-csv.mjs <output.csv> [rows]
 *
 * 注意：数据完全合成，不含任何真实业务或个人数据。
 * 注入的异常仅用于验证重复率/类型不一致统计：
 *   - 每 97 行复制上一行（整行重复）；
 *   - sales_amount 每 113 行为 "N/A"；
 *   - order_date 每 157 行为非法日期；
 *   - is_paid 每 47 行为 "unknown"（落在导入抽样窗口内，确保列按文本存储以便统计布尔异常）。
 */
import { createWriteStream } from 'node:fs'

const out = process.argv[2]
const rows = Number(process.argv[3] || 100000)
if (!out) {
  console.error('usage: node generate-synthetic-csv.mjs <output.csv> [rows]')
  process.exit(1)
}

const regions = ['华东', '华北', '华南', '西南', '东北']
const products = ['产品A', '产品B', '产品C']
const stream = createWriteStream(out, { encoding: 'utf8' })
stream.write('order_id,region,product,sales_amount,order_date,is_paid,note\n')

let previous = ''
for (let i = 1; i <= rows; i++) {
  if (i % 97 === 0 && previous) {
    stream.write(previous)
    continue
  }
  const amount = i % 113 === 0 ? 'N/A' : String((i * 37) % 9000 + 100)
  const day = String((i % 28) + 1).padStart(2, '0')
  const month = String((i % 12) + 1).padStart(2, '0')
  const date = i % 157 === 0 ? '2026/13/45' : `2026-${month}-${day}`
  const paid = i % 47 === 0 ? 'unknown' : (i % 7 === 0 ? 'false' : 'true')
  const line = `${i},${regions[i % regions.length]},${products[i % products.length]},${amount},${date},${paid},synthetic-${i}\n`
  stream.write(line)
  previous = line
}

stream.end(() => console.log(`generated ${rows} rows -> ${out}`))
