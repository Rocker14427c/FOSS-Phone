FROM eclipse-temurin:17-jdk-jammy

ARG ANDROID_COMMAND_LINE_TOOLS=12266719
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip python3 git \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl --fail --location --retry 3 \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS}_latest.zip" \
        -o /tmp/android-tools.zip \
    && unzip -q /tmp/android-tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-tools.zip /tmp/android-tools

# Only supported SDK packages. Do not request the removed legacy "tools" package.
RUN yes | sdkmanager --licenses >/dev/null
RUN sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

WORKDIR /workspace
COPY . /workspace
ENTRYPOINT ["bash", "tools/build-recording.sh"]
