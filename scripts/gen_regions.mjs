// 根据 scripts/sql/001_tb_address.sql 生成 frontend/src/data/regions.ts
// 输出：regions（级联树，value = id）、regionNameMap（id -> full_name）
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const src = readFileSync(resolve(__dirname, 'sql/001_tb_address.sql'), 'utf8')

// 匹配一行： (id,'name',level,parent_id,'code','province',city,district,'full_name',lng,lat)
const rowRe = /\((\d+),'([^']*)',(\d+),(\d+|NULL),'([^']*)','([^']*)',([^,]+),([^,]+),'([^']*)',[0-9.+-]+,[0-9.+-]+\)/g

function unq(v) {
  if (v === 'NULL') return null
  return v.replace(/^'(.*)'$/, '$1')
}

const rows = []
let m
while ((m = rowRe.exec(src)) !== null) {
  rows.push({
    id: Number(m[1]),
    name: m[2],
    level: Number(m[3]),
    parentId: m[4] === 'NULL' ? null : Number(m[4]),
    fullName: m[9],
  })
}

if (rows.length === 0) {
  console.error('未解析到任何地址行，请检查 001_tb_address.sql 格式')
  process.exit(1)
}

const byId = new Map(rows.map(r => [r.id, r]))

function buildChildren(parentId) {
  const kids = rows
    .filter(r => r.parentId === parentId)
    .sort((a, b) => a.id - b.id)
    .map(r => {
      const node = { value: r.id, label: r.name }
      const children = buildChildren(r.id)
      if (children.length) node.children = children
      return node
    })
  return kids
}

// 省级（parentId === null）作为根
const regions = rows
  .filter(r => r.parentId === null)
  .sort((a, b) => a.id - b.id)
  .map(r => {
    const node = { value: r.id, label: r.name }
    const children = buildChildren(r.id)
    if (children.length) node.children = children
    return node
  })

// id -> full_name 映射（展示用，address_id 永远指向三级行）
const regionNameMap = {}
for (const r of rows) {
  regionNameMap[r.id] = r.fullName
}

const header = `// 全国省市区静态数据（自动生成自 scripts/sql/001_tb_address.sql，勿手改）
// value = tb_address.id（数字），label = 本级名称
// 级联选择器配合 props.emitPath = false 直接取叶子 value（区县 id）作为 addressId 存储
// regionNameMap: id -> full_name（完整地址串），regionName() 用于展示

export interface RegionNode {
  value: number
  label: string
  children?: RegionNode[]
}

export const regions: RegionNode[] = ${JSON.stringify(regions)}

export const regionNameMap: Record<number, string> = ${JSON.stringify(regionNameMap)}

/** 根据地址 id 取完整地址串（如 广东省深圳市南山区），未命中返回空串 */
export function regionName(id?: number | null): string {
  if (id == null) return ''
  return regionNameMap[id] || ''
}
`

const outPath = resolve(__dirname, '../frontend/src/data/regions.ts')
writeFileSync(outPath, header, 'utf8')
console.log(`生成完成：${rows.length} 行，省份 ${regions.length} 个 → ${outPath}`)
