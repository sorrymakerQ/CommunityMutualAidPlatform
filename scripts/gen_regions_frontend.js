// ============================================================
// 从 scripts/sql/001_tb_address.sql 生成前端静态省市区数据
// 产出：frontend/src/data/regions.ts
//
// 背景：地址不再走 tb_address 表 + /address/list 接口，
//       改由前端写死省市区三级数据，级联选择器直接出「完整地址串」。
// 每个节点的 value = full_name（如 广东省深圳市南山区），
// label = 本级名称（如 南山区），cascader 用 emitPath:false 取叶子 value。
// ============================================================

const fs = require('fs');
const path = require('path');

const srcFile = path.join(__dirname, 'sql', '001_tb_address.sql');
const outFile = path.join(__dirname, '..', 'frontend', 'src', 'data', 'regions.ts');

const src = fs.readFileSync(srcFile, 'utf8');

function unquote(s) {
  s = s.trim();
  if (s === 'NULL') return null;
  if (s.startsWith("'")) s = s.slice(1, -1);
  return s.replace(/''/g, "'");
}

const nodes = new Map(); // id -> { id, parentId, name, fullName, level }

for (const raw of src.split('\n')) {
  const line = raw.trim();
  if (!line.startsWith('(')) continue; // 只处理数据行（每行一个 tuple）
  let inner = line.slice(1); // 去掉开头的 '('
  const close = inner.lastIndexOf(')');
  if (close < 0) continue;
  inner = inner.slice(0, close);
  const parts = inner.split(',');
  if (parts.length < 9) continue;

  const id = Number(parts[0]);
  const name = unquote(parts[1]);
  const level = Number(parts[2]);
  const parentId = parts[3] === 'NULL' ? null : Number(parts[3]);
  const fullName = unquote(parts[8]);
  nodes.set(id, { id, parentId, name, fullName, level });
}

// 按 parentId 分组，构建树
const byParent = new Map();
const roots = [];
for (const n of nodes.values()) {
  if (n.parentId == null) roots.push(n);
  else {
    const arr = byParent.get(n.parentId) || [];
    arr.push(n);
    byParent.set(n.parentId, arr);
  }
}

function toObj(n) {
  const o = { value: n.fullName, label: n.name };
  const children = byParent.get(n.id);
  if (children && children.length) {
    o.children = children.map(toObj);
  }
  return o;
}

const regions = roots.map(toObj);

const header =
  "// 全国省市区静态数据（自动生成自 scripts/sql/001_tb_address.sql，勿手改）\n" +
  "// value = 完整地址串（如 广东省深圳市南山区），label = 本级名称（如 南山区）\n" +
  "// 级联选择器配合 props.emitPath = false 直接取叶子 value 作为 address 存储\n\n" +
  "export interface RegionNode {\n" +
  "  value: string\n" +
  "  label: string\n" +
  "  children?: RegionNode[]\n" +
  "}\n\n" +
  "export const regions: RegionNode[] = ";

const body = JSON.stringify(regions);

fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, header + body + '\n', 'utf8');

const count = (arr) => arr.reduce((s, n) => s + 1 + (n.children ? count(n.children) : 0), 0);
console.log('已生成 ' + outFile);
console.log('节点总数:', count(regions), '省级:', regions.length);
console.log('文件大小:', (fs.statSync(outFile).size / 1024).toFixed(1) + ' KB');
