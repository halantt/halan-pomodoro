import zipfile, os
import argparse

HERE = os.path.dirname(os.path.abspath(__file__))

ap = argparse.ArgumentParser(description='把 assets + 已编译的 res/dex 组装成 unsigned.apk')
ap.add_argument('--build-dir', default=os.environ.get('FLOWTIME_BUILD', HERE),
                help='中间产物目录，需包含 base.apk 与 dex/classes.dex')
ap.add_argument('--app-dir', default=os.path.abspath(os.path.join(HERE, '..', 'app')),
                help='app 源码目录（默认仓库内的 app/）')
args = ap.parse_args()

build = os.path.abspath(args.build_dir)
proj = os.path.abspath(args.app_dir)
base = os.path.join(build, "base.apk")
out = os.path.join(build, "unsigned.apk")

zin = zipfile.ZipFile(base, 'r')
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)

def add(name, data, mode):
    zi = zipfile.ZipInfo(name)
    zi.compress_type = zipfile.ZIP_STORED if mode == 'stored' else zipfile.ZIP_DEFLATED
    zout.writestr(zi, data)

# 原样保留 base.apk 中的 manifest / res 图标 / arsc（arsc 必须未压缩）
for item in zin.infolist():
    data = zin.read(item.filename)
    mode = 'stored' if item.filename == 'resources.arsc' else 'deflate'
    add(item.filename, data, mode)
zin.close()

# dex
add('classes.dex', open(os.path.join(build, 'dex', 'classes.dex'), 'rb').read(), 'deflate')

# assets
asset_dir = os.path.join(proj, 'assets')
for root, _, files in os.walk(asset_dir):
    for f in files:
        full = os.path.join(root, f)
        rel = os.path.relpath(full, asset_dir).replace('\\', '/')
        add('assets/' + rel, open(full, 'rb').read(), 'deflate')

zout.close()

z = zipfile.ZipFile(out)
for i in z.infolist():
    m = 'STORED' if i.compress_type == 0 else 'DEFLATED'
    print(i.filename, m)
z.close()
