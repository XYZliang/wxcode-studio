FROM python:3.13-slim-bookworm AS native-build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates openjdk-17-jdk-headless \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz \
      -o /tmp/maven.tar.gz \
 && echo "a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b  /tmp/maven.tar.gz" \
      | sha512sum -c - \
 && tar -xzf /tmp/maven.tar.gz -C /opt && rm /tmp/maven.tar.gz
ENV PATH=/opt/apache-maven-3.9.9/bin:$PATH
WORKDIR /build
COPY native/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY native/src ./src
RUN mvn -B -q -DskipTests package && cp target/wxcode-bridge.jar /build/wxcode-bridge.jar
FROM python:3.13-slim-bookworm AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-17-jre-headless librsvg2-bin libseccomp2 ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --create-home --uid 10001 app
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY scripts ./scripts
COPY runtime.lock.json pyproject.toml ./
COPY examples ./examples
COPY --from=native-build /build/wxcode-bridge.jar ./native/wxcode-bridge.jar
COPY vendor/qbar ./vendor/qbar
RUN python scripts/fetch_runtime.py --check \
 && python scripts/extract_natives.py native/wxcode-bridge.jar native/libs \
 && chown -R app:app /app
ENV WXCODE_BRIDGE_JAR=/app/native/wxcode-bridge.jar \
    WXCODE_RUNTIME_DIR=/app/vendor/qbar \
    WXCODE_TMP_DIR=/tmp/wxcode \
    LD_LIBRARY_PATH=/app/native/libs \
    PYTHONUNBUFFERED=1
USER app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD python -c "import urllib.request,json,sys; \
d=json.load(urllib.request.urlopen('http://127.0.0.1:8000/api/health',timeout=8)); \
sys.exit(0 if d.get('status')=='ok' and d.get('runtime_ready') else 1)"
CMD ["uvicorn", "app.server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--timeout-keep-alive", "75"]