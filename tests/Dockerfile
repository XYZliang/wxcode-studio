FROM python:3.13-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends xvfb libnss3 libnspr4 libdbus-1-3 \
      libatk1.0-0 libatk-bridge2.0-0 libatspi2.0-0 libx11-6 libxcomposite1 libxdamage1 libxext6 \
      libxfixes3 libxrandr2 libgbm1 libxcb1 libxkbcommon0 libpango-1.0-0 libcairo2 libasound2 \
      openjdk-17-jre-headless librsvg2-bin libseccomp2 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /work
COPY requirements-dev.txt requirements.txt ./
RUN pip install --no-cache-dir -r requirements-dev.txt && python -m playwright install --with-deps chromium
CMD ["sh", "-c", "python -m pytest -q && python -m pytest -q -m e2e"]