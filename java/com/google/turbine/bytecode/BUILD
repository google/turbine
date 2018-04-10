package(default_visibility = ["//java/com/google/turbine:__subpackages__"])

java_library(
    name = "bytecode",
    srcs = glob(["*.java"]),
    visibility = [
        "//java/com/google/turbine:__subpackages__",
        "//third_party/bazel/src/java_tools/buildjar/java/com/google/devtools/build/java/turbine:__subpackages__",
        "//third_party/bazel/src/java_tools/buildjar/javatests/com/google/devtools/build/java/turbine:__subpackages__",
    ],
    deps = [
        "//java/com/google/turbine/model",
        "@guava//jar",
        "@jsr305//jar",
    ],
)
