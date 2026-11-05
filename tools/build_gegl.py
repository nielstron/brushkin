#!/usr/bin/env python3
"""Build the GEGL filter runtime from pinned sources for one Android ABI (called by CMake)."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a'], required=True)
p.add_argument('--meson', required=True)
a = p.parse_args()
root = a.output.resolve(); root.mkdir(parents=True, exist_ok=True)
prefix = root / 'prefix'
ndk = a.ndk / 'toolchains/llvm/prebuilt' / ('darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64') / 'bin'
triple, family, cpu = ('aarch64-linux-android', 'aarch64', 'aarch64') if a.abi == 'arm64-v8a' else ('armv7a-linux-androideabi', 'arm', 'armv7')
# The stamp changes with this script, the toolchain, or ABI; Gradle/CMake retains the build cache.
stamp = hashlib.sha256(Path(__file__).read_bytes() + str(a.ndk).encode() + a.abi.encode()).hexdigest()
if (root/'stamp').exists() and (root/'stamp').read_text() == stamp:
    raise SystemExit(0)

def run(args, label):
    with (root/(label+'.log')).open('w') as log:
        result = subprocess.run([str(x) for x in args], stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((root/(label+'.log')).read_text()[-12000:])
        raise SystemExit(result.returncode)

sources = [
 ('glib-2.84.4', 'https://download.gnome.org/sources/glib/2.84/', '8a9ea10943c36fc117e253f80c91e477b673525ae45762942858aef57631bb90'),
 ('babl-0.1.114', 'https://download.gimp.org/pub/babl/0.1/', 'bcbb7786c1e447703db3bc7fa34d62d0d2d117b22f04d8834c7b2d5ded456487'),
 ('json-glib-1.10.8', 'https://download.gnome.org/sources/json-glib/1.10/', '55c5c141a564245b8f8fbe7698663c87a45a7333c2a2c56f06f811ab73b212dd'),
 ('gegl-0.4.62', 'https://download.gimp.org/pub/gegl/0.4/', '5887576371ebf1d9e90797d10e4b9a7f1658228d4827583e79e1db3d94505c6c'),
]
for name, url, checksum in sources:
    archive = root/(name+'.tar.xz')
    if not archive.exists(): urllib.request.urlretrieve(url+archive.name, archive)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == checksum, archive
    if not (root/name).exists():
        with tarfile.open(archive) as tar: tar.extractall(root, filter='data')
# Upstream's libffi wrap tracks a branch; pin the tested commit instead.
wrap = root/'glib-2.84.4/subprojects/libffi.wrap'
wrap.write_text(wrap.read_text().replace('revision=meson', 'revision=83d0cfd00d7d37af4b4349511d29f1f0512621b3'))
cross = root/'android.ini'
cross.write_text(f"""[binaries]
c = '{ndk}/{triple}29-clang'
cpp = '{ndk}/{triple}29-clang++'
ar = '{ndk}/llvm-ar'
strip = '{ndk}/llvm-strip'
pkg-config = '{shutil.which('pkg-config')}'
[host_machine]
system = 'android'
cpu_family = '{family}'
cpu = '{cpu}'
endian = 'little'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['{prefix}/lib/pkgconfig']
[built-in options]
c_args = ['-Os', '-fPIC']
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384']
pkg_config_path = ['{prefix}/lib/pkgconfig']
""")

def build(name, source, options):
    dest = root/(name+'-build')
    setup = ['--reconfigure'] if (dest/'build.ninja').exists() else []
    run([a.meson, 'setup', *setup, dest, root/source, '--cross-file', cross, '--prefix', prefix,
         '--libdir', 'lib', '--buildtype=minsize', '-Dauto_features=disabled', *options], name+'-setup')
    run([a.meson, 'compile', '-C', dest, '-j', '6'], name+'-build')
    run([a.meson, 'install', '-C', dest], name+'-install')

build('glib', 'glib-2.84.4', ['-Dtests=false','-Dinstalled_tests=false','-Dintrospection=disabled',
 '-Ddocumentation=false','-Dman-pages=disabled','-Dnls=disabled','-Dlibmount=disabled',
 '-Dselinux=disabled','-Dxattr=false','-Dsysprof=disabled','-Ddefault_library=shared'])
build('babl', 'babl-0.1.114', ['-Dwith-docs=false','-Denable-gir=false','-Denable-vapi=false','-Dwith-lcms=false'])
build('json', 'json-glib-1.10.8', ['-Dtests=false','-Dconformance=false','-Dinstalled_tests=false'])
# Brushkin owns decoding/export. Exclude GEGL's external I/O modules and desktop tools.
# No placeholder codecs are linked; their dependencies disappear with those modules.
with tarfile.open(root/'gegl-0.4.62.tar.xz') as tar:
    for name in ['gegl-0.4.62/meson.build', 'gegl-0.4.62/operations/meson.build']:
        tar.extract(name, root, filter='data')
source = root/'gegl-0.4.62'
main = source/'meson.build'
s = main.read_text()
s = re.sub(r"libjpeg   = dependency\('libjpeg'.*", 'libjpeg = declare_dependency()', s)
s = re.sub(r"libpng    = dependency\('libpng'.*", 'libpng = declare_dependency()', s)
for directory in ['bin','examples','tests','perf','po','docs']:
    s = s.replace(f"subdir('{directory}')", f"# Brushkin omits {directory}")
main.write_text(s)
ops = source/'operations/meson.build'
ops.write_text(ops.read_text().replace("subdir('external')", '# Brushkin owns file I/O'))
build('gegl', 'gegl-0.4.62', ['-Ddocs=false','-Dintrospection=false','-Dvapigen=disabled','-Dworkshop=false'])
# Package only the main filter modules and their recursive Android dependency closure.
libs = {p.name:p for p in (prefix/'lib').rglob('*.so') if not p.is_symlink()}
names = {'libgegl-0.4.so','gegl-core.so','gegl-common.so','gegl-common-cxx.so',
         'gegl-common-gpl3.so','gegl-generated.so','transformops.so'}
names |= {p.name for p in (prefix/'lib/babl-0.1').glob('*.so')}
todo = list(names)
while todo:
    text = subprocess.check_output([ndk/'llvm-readelf','-d',libs[todo.pop()]], text=True)
    for dep in re.findall(r'Shared library: \[(.*?)\]', text):
        if dep in libs and dep not in names: names.add(dep); todo.append(dep)
        elif dep not in libs: assert dep in {'libc.so','libm.so','libdl.so','libz.so','liblog.so'}, dep
out = root/'jni'; out.mkdir(exist_ok=True)
for old in out.glob('*.so'): old.unlink()
for name in sorted(names):
    src = libs[name]
    new = ('libbabl_ext_'+name if src.parent.name == 'babl-0.1' else
           'libgegl_op_'+name if src.parent.name == 'gegl-0.4' else name)
    shutil.copy2(src, out/new)
    run([ndk/'llvm-strip','--strip-unneeded',out/new], 'strip')
(root/'stamp').write_text(stamp)
