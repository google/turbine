package(default_visibility = ["//java/com/google/turbine:__subpackages__"])

java_library(
    name = "bytecode",
    srcs = glob(["*.java"]),
    deps = [
        "//java/com/google/turbine/binder/bound",
        "//java/com/google/turbine/binder/env",
        "//java/com/google/turbine/binder/sym",
        "//java/com/google/turbine/bytecode",
        "//java/com/google/turbine/bytecode/sig",
        "//java/com/google/turbine/model",
        "//java/com/google/turbine/type",
        "@guava//jar",
        "@jsr305//jar",
    ],
)
