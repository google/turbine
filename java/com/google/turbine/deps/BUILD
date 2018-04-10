package(default_visibility = ["//java/com/google/turbine:__subpackages__"])

java_library(
    name = "deps",
    srcs = glob(["*.java"]),
    visibility = [
        "//java/com/google/turbine:__subpackages__",
        "//third_party/bazel/src/java_tools/buildjar/java/com/google/devtools/build/java/turbine:__subpackages__",
        "//third_party/bazel/src/java_tools/buildjar/javatests/com/google/devtools/build/java/turbine:__subpackages__",
    ],
    deps = [
        "//java/com/google/turbine/binder",
        "//java/com/google/turbine/binder/bound",
        "//java/com/google/turbine/binder/bytecode",
        "//java/com/google/turbine/binder/env",
        "//java/com/google/turbine/binder/sym",
        "//java/com/google/turbine/bytecode",
        "//java/com/google/turbine/lower",
        "//java/com/google/turbine/model",
        "//proto:deps_java_proto",
        "@guava//jar",
    ],
)
