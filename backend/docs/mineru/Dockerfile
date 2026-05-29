# GPU image for MinerU FastAPI service.
# Source: opendatalab/MinerU docker/global/Dockerfile
FROM vllm/vllm-openai:v0.11.2

ARG APT_MIRROR=https://mirrors.aliyun.com/ubuntu

RUN if [ -n "$APT_MIRROR" ] && [ -f /etc/apt/sources.list ]; then \
      sed -i "s|http://archive.ubuntu.com/ubuntu|$APT_MIRROR|g; s|http://security.ubuntu.com/ubuntu|$APT_MIRROR|g" /etc/apt/sources.list; \
    fi && \
    for i in 1 2 3; do \
      apt-get -o Acquire::Retries=5 update && break || { echo "apt-get update failed, retry $i/3"; sleep 5; }; \
    done && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      fonts-noto-core \
      fonts-noto-cjk \
      fontconfig \
      libgl1 && \
    fc-cache -fv && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install -U 'mineru[core]>=3.0.0' --break-system-packages && \
    python3 -m pip cache purge

COPY entrypoint.sh /usr/local/bin/mineru-entrypoint.sh
RUN chmod +x /usr/local/bin/mineru-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/mineru-entrypoint.sh"]
