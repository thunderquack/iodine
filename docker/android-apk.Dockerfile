FROM eclipse-temurin:17-jdk

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_NDK_ROOT=/opt/android-sdk/ndk/26.3.11579264
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

RUN apt-get update && apt-get install -y \
    wget unzip zip git make python3 rsync \
    libc6-i386 lib32stdc++6 lib32z1 \
    gradle \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools" \
    && mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "ndk;26.3.11579264"

WORKDIR /work
COPY . /work

RUN chmod +x scripts/build-android-native.sh scripts/build-android-apk.sh
RUN scripts/build-android-apk.sh

CMD ["bash", "-lc", "ls -lah android/app/build/outputs/apk/debug/ && echo APK: /work/android/app/build/outputs/apk/debug/app-debug.apk"]
