#!/usr/bin/env python3
"""Build this dependency-free Android lab with an existing JDK + Android SDK."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME')))
parser.add_argument('--build-tools', default='36.0.0')
args = parser.parse_args()
if not args.java_home or not args.sdk:
    parser.error('Specify --java-home and --sdk, or JAVA_HOME and ANDROID_SDK_ROOT.')
jdk = Path(args.java_home).expanduser().resolve()
sdk = Path(args.sdk).expanduser().resolve()
bt = sdk / 'build-tools' / args.build_tools
jar = sdk / 'platforms/android-36/android.jar'
suffix = '.exe' if os.name == 'nt' else ''
java = jdk / 'bin' / ('java' + suffix)
javac = jdk / 'bin' / ('javac' + suffix)
keytool = jdk / 'bin' / ('keytool' + suffix)
for path in [java, javac, keytool, jar, bt / ('aapt2' + suffix),
             bt / ('zipalign' + suffix), bt / 'lib/d8.jar', bt / 'lib/apksigner.jar']:
    if not path.is_file():
        parser.error(f'Missing tool: {path}')

def run(*command):
    subprocess.run([str(item) for item in command], cwd=ROOT, check=True)

build = ROOT / 'build'
dist = ROOT / 'dist'
build.mkdir(exist_ok=True)
dist.mkdir(exist_ok=True)
# Remove only this script's generated compiler outputs, avoiding stale classes.
for name in ['classes', 'dex', 'policy-tests']:
    path = build / name
    if path.exists():
        shutil.rmtree(path)
    path.mkdir()

sources = sorted((ROOT / 'app/src/main/java').rglob('*.java'))
run(javac, '-encoding', 'UTF-8', '--release', '8',
    '-classpath', jar, '-d', build / 'classes', *sources)
run(java, '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8',
    '--min-api', '26', '--lib', jar, '--output', build / 'dex',
    *sorted((build / 'classes').rglob('*.class')))

ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', 'edu.mocklab')
manifest.write(build / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
run(bt / ('aapt2' + suffix), 'link', '-I', jar,
    '--manifest', build / 'AndroidManifest.xml', '--min-sdk-version', '26',
    '--target-sdk-version', '36', '--version-code', '2', '--version-name', '0.2.0',
    '-o', build / 'unsigned.apk')
with zipfile.ZipFile(build / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
    for dex in sorted((build / 'dex').glob('*.dex')):
        archive.write(dex, dex.name)
run(bt / ('zipalign' + suffix), '-P', '16', '-f', '4',
    build / 'unsigned.apk', build / 'aligned.apk')

keystore = build / 'lab-debug.p12'
if not keystore.exists():
    run(keytool, '-genkeypair', '-storetype', 'PKCS12', '-keystore', keystore,
        '-storepass', 'android', '-keypass', 'android', '-alias', 'mocklab',
        '-dname', 'CN=Mock Location Lab Debug', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '3650')
output = dist / 'mock-location-lab.apk'
run(java, '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', keystore,
    '--ks-key-alias', 'mocklab', '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', output, build / 'aligned.apk')
run(java, '-jar', bt / 'lib/apksigner.jar', 'verify', '--verbose', output)
run(bt / ('zipalign' + suffix), '-c', '-P', '16', '4', output)

run(javac, '-encoding', 'UTF-8', '-d', build / 'policy-tests',
    ROOT / 'app/src/main/java/edu/mocklab/LocationPolicy.java',
    ROOT / 'tests/LocationPolicyTest.java')
run(java, '-cp', build / 'policy-tests', 'LocationPolicyTest')
print(f'APK: {output} ({output.stat().st_size} bytes)')
