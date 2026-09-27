# -*- coding: utf-8 -*-
"""离线 APK 构建脚本（无需完整 Android Studio）。

流程: aapt打包资源 -> ecj编译 -> d8转dex -> aapt合并 -> zipalign -> apksigner签名

用法示例:
  python build_apk.py                                  # 默认构建 _sensor_app (WatchSensor)
  python build_apk.py --app _watch_app --name WatchTextSync --min-sdk 24 --target-sdk 27
  python build_apk.py --app _phone_app --name PhoneTextSync --min-sdk 26 --target-sdk 28

源码 = <app目录>/src + <仓库根>/_common/src（存在则自动并入，供多 app 共享协议代码）。
"""
import argparse
import os
import shutil
import subprocess

BASE = os.path.dirname(os.path.abspath(__file__))            # _android_build
REPO = os.path.dirname(BASE)
COMMON_SRC = os.path.join(REPO, "_common", "src")
OUT = os.path.join(BASE, "out")
BUILD = os.path.join(BASE, "build")

# android.jar 查找顺序：本地持久 platform 目录（推荐，TEMP 会被系统清理） -> TEMP
_local_platform = os.path.join(BASE, "_platform27", "android-8.1.0", "android.jar")
android_jar = _local_platform if os.path.exists(_local_platform) else \
    os.path.join(os.environ.get("TEMP", "."), "android_api27.jar")
aapt = os.path.join(BASE, "android-9", "aapt.exe")
d8_jar = os.path.join(BASE, "android-9", "lib", "d8.jar")
apksigner_jar = os.path.join(BASE, "android-9", "lib", "apksigner.jar")
zipalign = os.path.join(BASE, "android-9", "zipalign.exe")
ecj = os.path.join(BASE, "ecj.jar")
java = "java"
keytool_candidates = [
    "keytool",
    r"C:\Program Files\Java\jre-1.8\bin\keytool.exe",
]


def run(cmd, desc):
    print("\n== %s ==\n  %s" % (desc, " ".join(cmd)))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.stdout:
        print(r.stdout[-2000:])
    if r.stderr:
        print("[stderr]", r.stderr[-2000:])
    if r.returncode != 0:
        print("!! 失败:", desc, "returncode", r.returncode)
        return False
    return True


def find_keytool():
    for k in keytool_candidates:
        try:
            r = subprocess.run([k, "-help"], capture_output=True, text=True)
            if r.returncode == 0:
                return k
        except Exception:
            continue
    return None


def main():
    ap = argparse.ArgumentParser(description="离线构建 APK")
    ap.add_argument("--app", default="_sensor_app", help="app 源码目录（相对仓库根或绝对路径）")
    ap.add_argument("--name", default=None, help="产物名（默认取目录名）")
    ap.add_argument("--min-sdk", type=int, default=24)
    ap.add_argument("--target-sdk", type=int, default=27)
    args = ap.parse_args()

    app_dir = args.app if os.path.isabs(args.app) else os.path.join(REPO, args.app)
    name = args.name or os.path.basename(os.path.normpath(app_dir))
    src_dirs = [os.path.join(app_dir, "src")]
    if os.path.isdir(COMMON_SRC):
        src_dirs.append(COMMON_SRC)

    if not os.path.exists(android_jar):
        print("!! android.jar 缺失:", android_jar)
        return
    for d in src_dirs:
        if not os.path.isdir(d):
            print("!! 源码目录缺失:", d)
            return

    shutil.rmtree(BUILD, ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    # 只清理当前 app 的旧产物，避免误删 out/ 里其他 app 的 APK
    for suffix in ("-unsigned.apk", "-aligned.apk", ".apk"):
        p = os.path.join(OUT, name + suffix)
        if os.path.exists(p):
            os.remove(p)

    src_dir = os.path.join(app_dir, "src")
    res_dir = os.path.join(app_dir, "res")
    manifest = os.path.join(app_dir, "AndroidManifest.xml")

    # 1. aapt: 打包资源 + 生成 R.java
    gen = os.path.join(BUILD, "gen")
    os.makedirs(gen, exist_ok=True)
    apk_unsigned = os.path.join(OUT, "%s-unsigned.apk" % name)
    if not run([aapt, "package", "-f", "-m",
                "-J", gen, "-M", manifest, "-S", res_dir,
                "-I", android_jar, "-F", apk_unsigned,
                "--min-sdk-version", str(args.min_sdk),
                "--target-sdk-version", str(args.target_sdk)],
               "aapt 打包资源"):
        return

    # 2. ECJ: 编译 Java 源码 (app src + 共享 src + R.java)
    classes = os.path.join(BUILD, "classes")
    os.makedirs(classes, exist_ok=True)
    java_files = []
    for root_dir in src_dirs:
        for root, _, files in os.walk(root_dir):
            for f in files:
                if f.endswith(".java"):
                    java_files.append(os.path.join(root, f))
    for root, _, files in os.walk(gen):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    if not run([java, "-jar", ecj,
                "-8", "-encoding", "UTF-8", "-classpath", android_jar,
                "-d", classes] + java_files, "ECJ 编译"):
        return

    # 3. d8: 转 dex
    dex_dir = os.path.join(BUILD, "dex")
    os.makedirs(dex_dir, exist_ok=True)
    class_files = []
    for root, _, files in os.walk(classes):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    if not run([java, "-jar", d8_jar, "--release", "--lib", android_jar,
                "--min-api", str(args.min_sdk),
                "--output", dex_dir] + class_files, "d8 转 dex"):
        return

    # 4. aapt: 把 dex 加进 APK（必须在 dex 目录下用相对路径，否则 dex 名会带完整路径）
    apk_dex = os.path.join(OUT, "%s-unsigned.apk" % name)
    old_cwd = os.getcwd()
    os.chdir(dex_dir)
    ok = run([aapt, "add", apk_dex, "classes.dex"], "aapt 添加 dex")
    os.chdir(old_cwd)
    if not ok:
        return

    # 5. zipalign
    apk_aligned = os.path.join(OUT, "%s-aligned.apk" % name)
    run([zipalign, "-f", "4", apk_dex, apk_aligned], "zipalign")

    # 6. 生成签名密钥（持久保存在工具链目录，保证跨构建签名一致，可覆盖安装）并 apksigner 签名
    keystore = os.path.join(BASE, "watch.keystore")
    if not os.path.exists(keystore):
        keytool = find_keytool()
        if keytool is None:
            print("!! 找不到 keytool，无法生成签名密钥")
            return
        run([keytool, "-genkeypair", "-v", "-keystore", keystore, "-alias", "watch",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", "watch123", "-keypass", "watch123",
             "-dname", "CN=WatchSync,O=AstrBot,C=CN"],
            "keytool 生成签名密钥")
    apk_signed = os.path.join(OUT, "%s.apk" % name)
    if not run([java, "-jar", apksigner_jar, "sign", "--ks", keystore,
                "--ks-pass", "pass:watch123", "--key-pass", "pass:watch123",
                "--out", apk_signed, apk_aligned], "apksigner 签名"):
        return

    print("\n=== 构建完成 ===")
    for f in sorted(os.listdir(OUT)):
        p = os.path.join(OUT, f)
        print("  %s (%d bytes)" % (f, os.path.getsize(p)))


if __name__ == "__main__":
    main()
