package(default_visibility = ["//java/com/google/turbine:__subpackages__"])

java_library(
    name = "main",
    srcs = glob(["*.java"]),
    deps = [
        "//java/com/google/turbine/binder",
        "//java/com/google/turbine/deps",
        "//java/com/google/turbine/diag",
        "//java/com/google/turbine/lower",
        "//java/com/google/turbine/options",
        "//java/com/google/turbine/parse",
        "//java/com/google/turbine/tree",
        "//java/com/google/turbine/zip",
        "//proto:deps_java_proto",
        "@guava//jar",
    ],
)
