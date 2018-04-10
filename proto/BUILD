package(default_visibility = ["//java/com/google/turbine:__subpackages__"])

proto_library(
    name = "deps_proto",
    srcs = ["deps.proto"],
)

java_proto_library(
    name = "deps_java_proto",
    deps = [":deps_proto"],
)
