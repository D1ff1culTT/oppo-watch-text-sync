# -*- coding: utf-8 -*-
"""下载离线构建 APK 所需的工具链（首次构建前运行一次）。

  1. android.jar (API 27)      -> _platform27/android-8.1.0/android.jar
  2. build-tools r28.0.3       -> android-9/   (aapt / zipalign / d8.jar / apksigner.jar)
  3. ECJ 编译器                -> ecj.jar

用法:
  pip install requests
  python _dl_tools.py
"""
import io
import os
import zipfile

import requests

BASE = os.path.dirname(os.path.abspath(__file__))
NO_PROXY = {'http': None, 'https': None}

# 与 build_apk.py 兼容的构建工具文件
NEEDED_TOOLS = [
    'aapt.exe',
    'zipalign.exe',
    'lib/d8.jar',
    'lib/apksigner.jar',
]


def fetch(url):
    print('下载:', url)
    r = requests.get(url, timeout=600, proxies=NO_PROXY)
    r.raise_for_status()
    return r.content


def android_jar():
    out = os.path.join(BASE, '_platform27', 'android-8.1.0', 'android.jar')
    if os.path.exists(out):
        print('已存在:', out)
        return
    z = zipfile.ZipFile(io.BytesIO(
        fetch('https://dl.google.com/android/repository/platform-27_r03.zip')))
    data = z.read('android-8.1.0/android.jar')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'wb') as f:
        f.write(data)
    print('完成:', out, len(data), 'bytes')


def build_tools():
    tools = os.path.join(BASE, 'android-9')
    if os.path.exists(os.path.join(tools, 'aapt.exe')):
        print('已存在:', tools)
        return
    z = zipfile.ZipFile(io.BytesIO(
        fetch('https://dl.google.com/android/repository/build-tools_r28.0.3-windows.zip')))
    names = z.namelist()
    root = names[0].split('/')[0]  # zip 内顶层目录
    os.makedirs(os.path.join(tools, 'lib'), exist_ok=True)
    for n in NEEDED_TOOLS:
        src = root + '/' + n
        dst = os.path.join(tools, *n.split('/'))
        with open(dst, 'wb') as f:
            f.write(z.read(src))
        print('完成:', dst)


def ecj():
    out = os.path.join(BASE, 'ecj.jar')
    if os.path.exists(out):
        print('已存在:', out)
        return
    urls = [
        'https://repo1.maven.org/maven2/org/eclipse/jdt/core/compiler/ecj/4.6.1/ecj-4.6.1.jar',
        'https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.46.100/ecj-3.46.100.jar',
    ]
    for url in urls:
        try:
            data = fetch(url)
            if len(data) > 1000000:
                with open(out, 'wb') as f:
                    f.write(data)
                print('完成:', out, len(data), 'bytes')
                return
        except Exception as e:  # noqa: BLE001
            print('fail:', e)
    print('!! ECJ 下载失败，请手动下载 ecj.jar 放到 _android_build/')


if __name__ == '__main__':
    android_jar()
    build_tools()
    ecj()
    print('\n工具链就绪，可用 build_apk.py 构建 APK')
