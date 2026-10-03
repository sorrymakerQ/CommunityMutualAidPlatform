"""扫描 Java 源码，找出「只有声明、没有调用点」的方法（保守版，只报告不删除）。

改进点（相对第一版）：
  1. 先剥离注释，避免把 javadoc 里提到的名字误算成调用
  2. 识别框架注解方法（@Bean/@PostConstruct/@PreDestroy/@EventListener/
     @ExceptionHandler/@Scheduled/@Async/...）——它们由 Spring 反射调用，不算死代码
  3. 排除 Controller（方法由 HTTP 调用）、Lombok 存取器、生命周期方法
"""
import re
import sys
from pathlib import Path

DECL = re.compile(
    r'^\s*(?:public|protected|private)\s+'
    r'(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?'
    r'(?:<[^>]+>\s+)?'
    r'([\w<>\[\],\.\? ]+?)\s+'
    r'(\w+)\s*\([^;]*$'
)

# 由框架/反射调用的注解，命中则不算死代码
FRAMEWORK_ANN = {
    'Bean', 'PostConstruct', 'PreDestroy', 'EventListener', 'ExceptionHandler',
    'Scheduled', 'Async', 'ControllerAdvice', 'RestControllerAdvice',
    'RocketMQMessageListener', 'Transactional', 'ConfigurationProperties',
    'InitBinder', 'ModelAttribute', 'EventListener',
}
SKIP_NAMES = {'main', 'equals', 'hashCode', 'toString', 'canEqual'}
GETTER_SETTER = re.compile(r'^(get|set|is)[A-Z]')

COMMENT_BLOCK = re.compile(r'/\*.*?\*/', re.S)
COMMENT_LINE = re.compile(r'//[^\n]*')


def strip_comments(text: str) -> str:
    text = COMMENT_BLOCK.sub(' ', text)
    text = COMMENT_LINE.sub(' ', text)
    return text


def scan(root: Path):
    files = list(root.rglob('*.java'))
    corpus = '\n'.join(strip_comments(f.read_text(encoding='utf-8', errors='replace')) for f in files)

    rows = []
    for f in files:
        rel = str(f.relative_to(root)).replace('\\', '/')
        if '/controller/' in rel:
            continue
        raw = f.read_text(encoding='utf-8', errors='replace')
        lines = raw.splitlines()
        for i, line in enumerate(lines, 1):
            m = DECL.match(line)
            if not m:
                continue
            ret, name = m.group(1).strip(), m.group(2)
            if name in SKIP_NAMES or GETTER_SETTER.match(name):
                continue
            if ret in ('new', 'return', 'else', 'if'):
                continue
            # 往上找 5 行内的注解（含 @Override 判断）
            anns = set()
            has_override = False
            for j in range(max(0, i - 6), i - 1):
                s = lines[j].strip()
                if s.startswith('@'):
                    a = re.match(r'@(\w+)', s)
                    if a:
                        anns.add(a.group(1))
                if s.startswith('@Override'):
                    has_override = True
            occ = len(re.findall(r'\b' + re.escape(name) + r'\s*\(', corpus))
            fw = anns & FRAMEWORK_ANN
            rows.append(dict(file=rel, line=i, name=name, occ=occ,
                             override=has_override, fw=sorted(fw)))
    return rows


def main():
    rows = scan(Path(sys.argv[1]))
    dead = [r for r in rows if r['occ'] <= 1 and not r['fw']]

    print('=' * 100)
    print('✅ 确定候选：剥离注释后仍然「只有声明、零调用」，且无框架注解')
    print('=' * 100)
    for r in sorted(dead, key=lambda x: (x['file'], x['line'])):
        ov = '  [@Override]' if r['override'] else ''
        print(f"{r['file']}:{r['line']:<5} {r['name']}{ov}")

    print()
    print('=' * 100)
    print('⚠️  被排除：有框架注解（Spring 反射调用，不能删）')
    print('=' * 100)
    fw = [r for r in rows if r['occ'] <= 2 and r['fw']]
    for r in sorted(fw, key=lambda x: (x['file'], x['line'])):
        print(f"{r['file']}:{r['line']:<5} {r['name']:<30} {' '.join('@' + a for a in r['fw'])}")


if __name__ == '__main__':
    main()
