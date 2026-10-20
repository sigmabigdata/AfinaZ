# ============================================================
# AfinaZ · Dockerfile приложения
# ============================================================
# Runtime: Astra Linux SE 1.8 + Axiom JDK Pro 17 + КриптоПро CSP 5.0
# Build:   Maven + Temurin (для компиляции JDK-поставщик не критичен)
#
# ВАЖНО: базовый образ runtime — community (epicmorg/astralinux:1.8-light).
# Для боевой сдачи заменить на официальный из registry.astralinux.ru.
# ============================================================

# ---------- Стадия сборки ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

ENV MAVEN_OPTS="-Xmx512m -Xms128m"

# JCSP JAR устанавливаем в локальный .m2
COPY libs ./libs
RUN cd libs && \
    VERSION=5.0.49800 && G=ru.cryptopro.jcsp && \
    for j in JCSP:jcsp JCP:jcp JCryptoP:jcryptop JCPRevCheck:jcp-revcheck \
             JCPRevTools:jcp-revtools CAdES:cades AdES-core:ades-core \
             ASN1P:asn1p asn1rt:asn1rt cmsutil:cmsutil JCPxml:jcp-xml \
             XAdES:xades XMLDSigRI:xmldsigri Rutoken:rutoken; do \
        f="${j%%:*}"; a="${j##*:}"; \
        mvn -B -q install:install-file -Dfile="$f.jar" \
            -DgroupId=$G -DartifactId=$a -Dversion=$VERSION -Dpackaging=jar; \
    done && \
    mvn -B -q install:install-file -Dfile=bcprov-jdk15on-1.70.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcprov-jdk15on -Dversion=1.70 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcpkix-jdk15on-1.70.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcpkix-jdk15on -Dversion=1.70 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcutil-jdk15on-1.70.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcutil-jdk15on -Dversion=1.70 -Dpackaging=jar

COPY pom.xml ./
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Стадия исполнения ----------
FROM epicmorg/astralinux:1.8-light
WORKDIR /app

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=ru_RU.UTF-8 \
    LC_ALL=ru_RU.UTF-8 \
    JAVA_HOME=/opt/axiom-jdk

# Настройки apt: увеличенные таймауты и ретраи
RUN printf 'Acquire::Retries "10";\n\
Acquire::http::Timeout "120";\n\
Acquire::https::Timeout "120";\n' > /etc/apt/apt.conf.d/80-afinaz-timeouts

RUN printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && \
    chmod +x /usr/sbin/policy-rc.d

# Системные утилиты + библиотеки для КриптоПро + pg-клиент
RUN apt-get update -o Acquire::Retries=10 && \
    apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg tzdata locales \
        pcscd libpcsclite1 openssl \
        postgresql-client-15 \
        libparsec-base3 libparsec-mac3 \
    && rm -rf /var/lib/apt/lists/* \
    && localedef -i ru_RU -c -f UTF-8 ru_RU.UTF-8 || true

# Axiom JDK 17 Pro — установка из tar.gz (сертифицированная версия)
COPY axiom-dist/axiomjdk-jdk-pro17.0.20.1+1-linux-amd64.tar.gz /tmp/axiom.tgz
RUN mkdir -p /opt/axiom-jdk && \
    tar -xzf /tmp/axiom.tgz -C /opt/axiom-jdk --strip-components=1 && \
    rm /tmp/axiom.tgz && \
    /opt/axiom-jdk/bin/java -version 2>&1

ENV JAVA_HOME=/opt/axiom-jdk
ENV PATH="/opt/axiom-jdk/bin:${PATH}"

# ---- КриптоПро CSP 5.0 R2 ----
# Ставим только серверные пакеты. GUI, apache, nginx, токены — не нужны.
# Явно указываем kc1 перед capilite (apt не всегда сам разрешает порядок).
COPY cryptopro-dist/linux-amd64_deb.tgz /tmp/cryptopro/
RUN cd /tmp/cryptopro && \
    tar xzf linux-amd64_deb.tgz && \
    cd linux-amd64_deb && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        ./lsb-cprocsp-base_*.deb \
        ./lsb-cprocsp-rdr-64_*.deb \
        ./lsb-cprocsp-kc1-64_*.deb \
        ./lsb-cprocsp-capilite-64_*.deb \
        ./cprocsp-compat-debian_*.deb \
        ./cprocsp-curl-64_*.deb \
        ./cprocsp-pki-cades-64_*.deb \
        ./lsb-cprocsp-ca-certs_*.deb \
    && rm -rf /tmp/cryptopro /var/lib/apt/lists/* && \
    ls -la /opt/cprocsp/bin/amd64/ 2>&1 | head -10

ENV PATH="/opt/cprocsp/bin/amd64:/opt/cprocsp/sbin/amd64:${PATH}"
ENV LD_LIBRARY_PATH="/opt/cprocsp/lib/amd64"

RUN rm -f /usr/sbin/policy-rc.d

# Непривилегированный пользователь
RUN groupadd -r afina 2>/dev/null || true && \
    useradd -r -g afina -m -d /home/afina afina 2>/dev/null || true && \
    mkdir -p /app/storage/documents /var/log/afina /opt/afinaz/secrets \
             /var/opt/cprocsp/tmp /var/opt/cprocsp/keys/afina && \
    chown -R afina:afina /app /var/log/afina /var/opt/cprocsp /opt/afinaz && \
    chmod -R 775 /var/opt/cprocsp && \
    chmod 777 /var/opt/cprocsp/tmp

# JAR из build-стадии
COPY --from=build /app/target/ukep-sign-service.jar app.jar

# Сертификаты УЦ
COPY certs/ /app/certs/

ENV JAVA_TOOL_OPTIONS="-Djava.util.prefs.userRoot=/tmp/.java -Djava.util.prefs.systemRoot=/tmp/.java-system"

VOLUME ["/app/storage", "/var/log/afina"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

USER afina

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
