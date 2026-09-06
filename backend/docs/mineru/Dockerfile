FROM python:3.12-slim-bookworm

ARG APT_MIRROR=https://mirrors.aliyun.com/debian

RUN if [ -n "$APT_MIRROR" ]; then \
      sed -i "s|http://deb.debian.org/debian|$APT_MIRROR|g; s|http://deb.debian.org/debian-security|$APT_MIRROR-security|g" /etc/apt/sources.list.d/debian.sources; \
    fi \
    && apt-get -o Acquire::Retries=5 update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl fonts-noto-core fonts-noto-cjk fontconfig libgl1 libglib2.0-0 \
    && fc-cache -fv \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu 'torch==2.6.0+cpu' 'torchvision==0.21.0+cpu'
RUN python3 -m pip install --no-cache-dir -U 'mineru[pipeline]==3.4.5'
RUN python3 -m pip install --no-cache-dir 'six==1.17.0'

COPY cancellable_api.py /app/cancellable_api.py
WORKDIR /app

COPY entrypoint.sh /usr/local/bin/mineru-entrypoint.sh
RUN chmod +x /usr/local/bin/mineru-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/mineru-entrypoint.sh"]
