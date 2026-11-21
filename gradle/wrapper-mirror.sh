#!/usr/bin/env bash
# 令 gradle-wrapper.properties 指向腾讯云镜像，并去掉 distributionSha256Sum。
# 为什么不提交 distributionSha256Sum：见 AGENTS.md「命令与代价」的 Wrapper 条目。
# 用法: bash gradle/wrapper-mirror.sh <gradle-wrapper.properties>
# 退出码: 0 已改写, 3 无需改动, 2 参数或格式不对
set -euo pipefail

file="${1:-}"
if [[ -z "$file" || ! -f "$file" ]]; then
    echo "usage: bash gradle/wrapper-mirror.sh <gradle-wrapper.properties>" >&2
    exit 2
fi

raw="$(sed -n 's/^distributionUrl=//p' "$file" | tr -d '\r')"
url="${raw//\\:/:}"
case "$url" in
    https://services.gradle.org/distributions/gradle-*-bin.zip) needs_url=1 ;;
    https://mirrors.cloud.tencent.com/gradle/gradle-*-bin.zip) needs_url=0 ;;
    *)
        echo "无法识别的 distributionUrl: $url" >&2
        exit 2
        ;;
esac

needs_sum=0
if grep -q '^distributionSha256Sum=' "$file"; then needs_sum=1; fi

if [[ $needs_url == 0 && $needs_sum == 0 ]]; then
    exit 3
fi

sed -i -e '/^distributionUrl=/s|services[.]gradle[.]org/distributions/|mirrors.cloud.tencent.com/gradle/|' \
       -e '/^distributionSha256Sum=/d' \
       "$file"
