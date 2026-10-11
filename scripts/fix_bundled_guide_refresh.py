from pathlib import Path

p = Path('app/src/main/java/com/foldbook/app/MainActivity.kt')
s = p.read_text()
old = '''                if (refreshed != null) {
                    library = refreshed
                }'''
new = '''                if (refreshed != null) {
                    library = BundledGuide.withGuide(context, refreshed)
                }'''
count = s.count(old)
if count != 2:
    raise SystemExit(f'expected 2 refreshed-library blocks, found {count}')
s = s.replace(old, new)
p.write_text(s)
print('Kept bundled guide after rename/delete refreshes')
