# -*- coding: utf-8 -*-
import requests, os, sys

out = r'E:\Learn\astrbot\astrbot_plugin_oppo_watch\_android_build\ecj.jar'
urls = [
    'https://repo1.maven.org/maven2/org/eclipse/jdt/core/compiler/ecj/4.6.1/ecj-4.6.1.jar',
    'https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.46.100/ecj-3.46.100.jar',
]
for url in urls:
    try:
        print('尝试:', url[:80])
        r = requests.get(url, timeout=60, proxies={'http': None, 'https': None})
        if r.status_code == 200 and len(r.content) > 1000000:
            open(out, 'wb').write(r.content)
            print('ECJ 成功:', len(r.content), 'bytes')
            sys.exit(0)
        print('  status=%s len=%s' % (r.status_code, len(r.content)))
    except Exception as e:
        print('  fail', e)
print('全部失败')
