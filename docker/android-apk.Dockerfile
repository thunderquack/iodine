FROM eclipse-temurin:17-jdk AS builder

ARG DEBIAN_FRONTEND=noninteractive
ARG ANDROID_CMDLINE_TOOLS_ZIP=commandlinetools-linux-13114758_latest.zip
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=34.0.0
ARG ANDROID_NDK_VERSION=26.3.11579264
ARG GRADLE_VERSION=8.7

RUN apt-get update && apt-get install -y \
    wget unzip zip git make python3 rsync \
    libc6-i386 lib32stdc++6 lib32z1 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && wget -q "https://dl.google.com/android/repository/${ANDROID_CMDLINE_TOOLS_ZIP}" -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip

RUN wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle \
    && rm /tmp/gradle.zip

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_NDK_ROOT=/opt/android-sdk/ndk/${ANDROID_NDK_VERSION}
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "ndk;${ANDROID_NDK_VERSION}"

WORKDIR /work
COPY . /work

RUN sed -i 's/\r$//' /work/scripts/build-android-native.sh /work/scripts/build-android-apk.sh /work/src/osflags

RUN chmod +x scripts/build-android-native.sh scripts/build-android-apk.sh
RUN mkdir -p /out && APK_OUT_DIR=/out scripts/build-android-apk.sh

FROM scratch AS export

COPY --from=builder /out/ /out/
