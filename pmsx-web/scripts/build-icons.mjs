// src 에서 사용하는 Remix 아이콘('ri-xxx')만 골라 오프라인 아이콘 묶음을 만든다.
// 전체 컬렉션(약 900KB)을 번들에 넣지 않기 위함. dev/build 전에 자동 실행된다.
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, extname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const srcDir = join(root, 'src')
const outFile = join(srcDir, 'plugins', 'iconify', 'ri-subset.json')
const collection = JSON.parse(readFileSync(join(root, 'node_modules', '@iconify-json', 'ri', 'icons.json'), 'utf8'))

const walk = (dir) => readdirSync(dir).flatMap((name) => {
  const p = join(dir, name)
  return statSync(p).isDirectory() ? walk(p) : [p]
})

const used = new Set()
for (const file of walk(srcDir)) {
  if (!['.ts', '.vue'].includes(extname(file)) || file === outFile) continue
  for (const m of readFileSync(file, 'utf8').matchAll(/['"`]ri-([a-z0-9-]+)['"`]/g)) used.add(m[1])
}

const icons = {}
const aliases = {}
const missing = []
for (const name of used) {
  if (collection.icons[name]) icons[name] = collection.icons[name]
  else if (collection.aliases?.[name]) {
    aliases[name] = collection.aliases[name]
    const parent = collection.aliases[name].parent
    icons[parent] = collection.icons[parent]
  } else missing.push(name)
}

if (missing.length) {
  console.error(`[icons] Remix 아이콘에 없는 이름: ${missing.join(', ')}`)
  process.exit(1)
}

const subset = { prefix: collection.prefix, width: collection.width, height: collection.height, icons, aliases }
writeFileSync(outFile, JSON.stringify(subset))
console.warn(`[icons] ${Object.keys(icons).length}개 아이콘 → src/plugins/iconify/ri-subset.json`)
